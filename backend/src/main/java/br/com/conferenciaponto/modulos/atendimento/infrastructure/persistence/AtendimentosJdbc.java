package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ResumoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.AnexoLido;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Cabecalho;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** atendimento.atendimento e os anexos do PDF em atendimento.arquivo. Toda consulta filtra pelo dono. */
@Repository
class AtendimentosJdbc implements Atendimentos {

    private static final String RESUMO = """
            SELECT a.id, a.chamado_digisac, a.contato, a.inicio, a.fim, a.situacao, a.criado_em, a.links_validos_ate,
                   (SELECT count(*) FROM jsonb_array_elements(a.conversa -> 'itens') i WHERE i ->> 'tipo' = 'mensagem')
                       AS mensagens,
                   (SELECT count(*) FROM atendimento.arquivo f WHERE f.atendimento_id = a.id) AS anexos
              FROM atendimento.atendimento a
            """;

    private final JdbcClient jdbc;
    private final ConversaJson conversas;

    /** A parte do atendimento que só o detalhe usa. */
    private record Guardado(String conversa, int versaoLeitor, Instant apagarArquivosEm) {
    }

    AtendimentosJdbc(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.conversas = new ConversaJson(json);
    }

    @Override
    public void salvarNovo(NovoAtendimento novo) {
        Cabecalho cabecalho = novo.conversa().cabecalho();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, contato, inicio, fim, situacao,
                               conversa, versao_leitor, links_validos_ate, criado_em, atualizado_em, apagar_arquivos_em,
                               apagar_textos_em)
                        VALUES (:id, :usuario, :chamado, :contato, :inicio, :fim, :situacao, CAST(:conversa AS jsonb),
                                :versaoLeitor, :links, :criado, :criado, :apagarArquivos, :apagarTextos)
                        """)
                .param("id", novo.id())
                .param("usuario", novo.usuarioId())
                .param("chamado", cabecalho.chamado())
                .param("contato", cabecalho.contato())
                .param("inicio", utc(cabecalho.inicio()))
                .param("fim", utc(cabecalho.fim()))
                .param("situacao", SituacaoDoAtendimento.NOVO.codigo())
                .param("conversa", conversas.escrever(novo.conversa(), novo.omitidos()))
                .param("versaoLeitor", (short) novo.versaoLeitor())
                .param("links", utc(novo.conversa().linksValidosAte()))
                .param("criado", utc(novo.criadoEm()))
                .param("apagarArquivos", utc(novo.apagarArquivosEm()))
                .param("apagarTextos", utc(novo.apagarTextosEm()))
                .update();
        for (AnexoLido anexo : novo.conversa().anexos()) {
            jdbc.sql("""
                            INSERT INTO atendimento.arquivo (atendimento_id, origem, ordem, nome_original, categoria, tipo_midia,
                                   momento, momento_fonte, pagina, posicao_y, mensagem_ordem, url_download, url_valida_ate,
                                   situacao, criado_em)
                            VALUES (:atendimento, 'anexo_conversa', :ordem, :nome, :categoria, :tipo, :momento, :fonte,
                                    :pagina, :y, :mensagem, :url, :validoAte, 'aguardando', :criado)
                            """)
                    .param("atendimento", novo.id())
                    .param("ordem", (short) anexo.ordem())
                    .param("nome", anexo.nome())
                    .param("categoria", anexo.categoria().codigo())
                    .param("tipo", anexo.tipoDeMidia())
                    .param("momento", utc(anexo.momento()))
                    .param("fonte", anexo.momento() == null ? "sem_horario" : "mensagem")
                    .param("pagina", (short) anexo.pagina())
                    .param("y", anexo.y())
                    .param("mensagem", anexo.mensagemOrdem())
                    .param("url", anexo.url())
                    .param("validoAte", utc(anexo.validoAte()))
                    .param("criado", utc(novo.criadoEm()))
                    .update();
        }
    }

    @Override
    public List<ResumoDoAtendimento> doUsuario(UUID usuarioId) {
        return jdbc.sql(RESUMO + " WHERE a.usuario_id = :usuario ORDER BY a.criado_em DESC")
                .param("usuario", usuarioId)
                .query(AtendimentosJdbc::resumo).list();
    }

    @Override
    public Optional<AtendimentoGuardado> buscar(UUID id, UUID usuarioId) {
        return jdbc.sql("""
                        SELECT a.conversa::text AS conversa_json, a.versao_leitor, a.apagar_arquivos_em
                          FROM atendimento.atendimento a WHERE a.id = :id AND a.usuario_id = :usuario
                        """)
                .param("id", id)
                .param("usuario", usuarioId)
                .query((rs, linha) -> new Guardado(rs.getString("conversa_json"), rs.getInt("versao_leitor"),
                        instante(rs, "apagar_arquivos_em")))
                .optional()
                .map(guardado -> {
                    ResumoDoAtendimento resumo = jdbc.sql(RESUMO + " WHERE a.id = :id")
                            .param("id", id).query(AtendimentosJdbc::resumo).single();
                    List<ArquivoDoAtendimento> arquivos = jdbc.sql("""
                                    SELECT id, origem, ordem, nome_original, categoria, situacao, url_valida_ate,
                                           mensagem_ordem, momento
                                      FROM atendimento.arquivo WHERE atendimento_id = :id ORDER BY origem, ordem
                                    """)
                            .param("id", id)
                            .query(AtendimentosJdbc::arquivo).list();
                    return new AtendimentoGuardado(resumo, conversas.ler(guardado.conversa()), arquivos,
                            guardado.versaoLeitor(), guardado.apagarArquivosEm());
                });
    }

    @Override
    public boolean existe(UUID id, UUID usuarioId) {
        return jdbc.sql("SELECT count(*) FROM atendimento.atendimento WHERE id = :id AND usuario_id = :usuario")
                .param("id", id)
                .param("usuario", usuarioId)
                .query(Long.class).single() > 0;
    }

    @Override
    public Optional<ResumoDoAtendimento> doChamado(UUID usuarioId, String chamado) {
        return jdbc.sql(RESUMO + " WHERE a.usuario_id = :usuario AND a.chamado_digisac = :chamado"
                        + " ORDER BY a.criado_em DESC LIMIT 1")
                .param("usuario", usuarioId)
                .param("chamado", chamado)
                .query(AtendimentosJdbc::resumo).optional();
    }

    @Override
    public boolean apagar(UUID id, UUID usuarioId) {
        return jdbc.sql("DELETE FROM atendimento.atendimento WHERE id = :id AND usuario_id = :usuario")
                .param("id", id)
                .param("usuario", usuarioId)
                .update() > 0;
    }

    private static ResumoDoAtendimento resumo(ResultSet rs, int linha) throws SQLException {
        return new ResumoDoAtendimento(rs.getObject("id", UUID.class), rs.getString("chamado_digisac"),
                rs.getString("contato"), instante(rs, "inicio"), instante(rs, "fim"),
                SituacaoDoAtendimento.doCodigo(rs.getString("situacao")), instante(rs, "criado_em"),
                rs.getInt("mensagens"), rs.getInt("anexos"), instante(rs, "links_validos_ate"));
    }

    private static ArquivoDoAtendimento arquivo(ResultSet rs, int linha) throws SQLException {
        return new ArquivoDoAtendimento(rs.getObject("id", UUID.class), rs.getString("origem"), rs.getInt("ordem"),
                rs.getString("nome_original"), CategoriaDoArquivo.doCodigo(rs.getString("categoria")),
                rs.getString("situacao"), instante(rs, "url_valida_ate"), rs.getObject("mensagem_ordem", Integer.class),
                instante(rs, "momento"));
    }

    private static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
