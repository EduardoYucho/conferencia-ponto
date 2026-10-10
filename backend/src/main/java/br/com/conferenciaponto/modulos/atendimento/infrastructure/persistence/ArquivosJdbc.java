package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoParaBaixar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConteudoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.OrigemDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.AnexoLido;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.instante;
import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.utc;

/** atendimento.arquivo: anexos da conversa (com o link até o download) e os arquivos enviados pela pessoa. */
@Repository
class ArquivosJdbc implements Arquivos {

    /** Ainda não resolvido: pode receber falha, novo link ou o download. */
    private static final String EM_ABERTO = "('aguardando', 'baixando', 'falhou', 'vencido')";

    private static final String COLUNAS = """
            id, origem, ordem, nome_original, categoria, situacao, url_valida_ate, mensagem_ordem, momento, tamanho,
            erro_codigo, erro_mensagem, caminho
            """;

    private final JdbcClient jdbc;

    ArquivosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static List<ArquivoDoAtendimento> doAtendimento(JdbcClient jdbc, UUID atendimentoId) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM atendimento.arquivo WHERE atendimento_id = :id"
                        + " ORDER BY CASE origem WHEN 'anexo_conversa' THEN 0 WHEN 'ligacao' THEN 1 WHEN 'video' THEN 2 ELSE 3 END, ordem")
                .param("id", atendimentoId)
                .query(ArquivosJdbc::arquivo).list();
    }

    @Override
    public List<ArquivoDoAtendimento> doAtendimento(UUID atendimentoId) {
        return doAtendimento(jdbc, atendimentoId);
    }

    @Override
    public Optional<ArquivoDoAtendimento> buscar(UUID atendimentoId, UUID arquivoId) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM atendimento.arquivo WHERE atendimento_id = :atendimento AND id = :id")
                .param("atendimento", atendimentoId)
                .param("id", arquivoId)
                .query(ArquivosJdbc::arquivo).optional();
    }

    @Override
    public Optional<ArquivoParaBaixar> paraBaixar(UUID arquivoId) {
        return jdbc.sql("""
                        SELECT id, atendimento_id, ordem, nome_original, url_download, url_valida_ate, situacao
                          FROM atendimento.arquivo WHERE id = :id AND origem = 'anexo_conversa'
                        """)
                .param("id", arquivoId)
                .query(ArquivosJdbc::paraBaixar).optional();
    }

    @Override
    public List<ArquivoParaBaixar> aBaixar(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT id, atendimento_id, ordem, nome_original, url_download, url_valida_ate, situacao
                          FROM atendimento.arquivo
                         WHERE atendimento_id = :atendimento AND origem = 'anexo_conversa'
                           AND situacao IN ('aguardando', 'falhou') AND url_download IS NOT NULL
                         ORDER BY ordem
                        """)
                .param("atendimento", atendimentoId)
                .query(ArquivosJdbc::paraBaixar).list();
    }

    @Override
    public int vencerLinks(UUID atendimentoId, Instant agora, String mensagem) {
        return jdbc.sql("""
                        UPDATE atendimento.arquivo
                           SET situacao = 'vencido', erro_codigo = 'LINK_VENCIDO', erro_mensagem = :mensagem
                         WHERE atendimento_id = :atendimento AND origem = 'anexo_conversa'
                           AND situacao IN ('aguardando', 'falhou') AND url_valida_ate IS NOT NULL AND url_valida_ate <= :agora
                        """)
                .param("atendimento", atendimentoId)
                .param("agora", utc(agora))
                .param("mensagem", cortar(mensagem, 500))
                .update();
    }

    @Override
    public boolean marcarBaixando(UUID arquivoId) {
        return jdbc.sql("""
                        UPDATE atendimento.arquivo SET situacao = 'baixando'
                         WHERE id = :id AND situacao IN ('aguardando', 'falhou', 'baixando') AND url_download IS NOT NULL
                        """)
                .param("id", arquivoId)
                .update() > 0;
    }

    @Override
    public boolean concluirDownload(UUID arquivoId, ConteudoGuardado conteudo, Instant agora, String mensagemSeNaoSuportado) {
        return jdbc.sql("""
                        UPDATE atendimento.arquivo
                           SET situacao = :situacao, tamanho = :tamanho, sha256 = :sha, caminho = :caminho,
                               categoria = :categoria, tipo_midia = :tipo, url_download = NULL,
                               erro_codigo = :codigo, erro_mensagem = :mensagem
                         WHERE id = :id AND situacao = 'baixando'
                        """)
                .param("id", arquivoId)
                .params(conteudo(conteudo, mensagemSeNaoSuportado))
                .update() > 0;
    }

    @Override
    public void registrarFalha(UUID arquivoId, SituacaoDoArquivo situacao, String codigo, String mensagem) {
        jdbc.sql("""
                        UPDATE atendimento.arquivo SET situacao = :situacao, erro_codigo = :codigo, erro_mensagem = :mensagem
                         WHERE id = :id AND situacao IN %s
                        """.formatted(EM_ABERTO))
                .param("id", arquivoId)
                .param("situacao", situacao.codigo())
                .param("codigo", cortar(codigo, 40))
                .param("mensagem", cortar(mensagem, 500))
                .update();
    }

    @Override
    public void aguardar(UUID arquivoId) {
        jdbc.sql("""
                        UPDATE atendimento.arquivo SET situacao = 'aguardando', erro_codigo = NULL, erro_mensagem = NULL
                         WHERE id = :id AND situacao IN %s
                        """.formatted(EM_ABERTO))
                .param("id", arquivoId)
                .update();
    }

    @Override
    public void guardarConteudo(UUID arquivoId, ConteudoGuardado conteudo, Instant agora, String mensagemSeNaoSuportado) {
        jdbc.sql("""
                        UPDATE atendimento.arquivo
                           SET situacao = :situacao, tamanho = :tamanho, sha256 = :sha, caminho = :caminho,
                               categoria = :categoria, tipo_midia = :tipo, url_download = NULL,
                               erro_codigo = :codigo, erro_mensagem = :mensagem
                         WHERE id = :id
                        """)
                .param("id", arquivoId)
                .params(conteudo(conteudo, mensagemSeNaoSuportado))
                .update();
    }

    @Override
    public int proximaOrdem(UUID atendimentoId, OrigemDoArquivo origem) {
        return jdbc.sql("SELECT coalesce(max(ordem), 0) + 1 FROM atendimento.arquivo WHERE atendimento_id = :a AND origem = :o")
                .param("a", atendimentoId)
                .param("o", origem.codigo())
                .query(Integer.class).single();
    }

    @Override
    public boolean inserir(NovoArquivo novo) {
        ConteudoGuardado c = novo.conteudo();
        try {
            jdbc.sql("""
                            INSERT INTO atendimento.arquivo (id, atendimento_id, origem, ordem, nome_original, categoria,
                                   tipo_midia, tamanho, sha256, caminho, momento, momento_fonte, situacao, criado_em)
                            VALUES (:id, :atendimento, :origem, :ordem, :nome, :categoria, :tipo, :tamanho, :sha, :caminho,
                                    :momento, :fonte, 'pronto', :criado)
                            """)
                    .param("id", novo.id())
                    .param("atendimento", novo.atendimentoId())
                    .param("origem", novo.origem().codigo())
                    .param("ordem", (short) novo.ordem())
                    .param("nome", cortar(novo.nomeOriginal(), 255))
                    .param("categoria", c.categoria().codigo())
                    .param("tipo", cortar(c.tipoDeMidia(), 100))
                    .param("tamanho", c.tamanho())
                    .param("sha", c.sha256())
                    .param("caminho", cortar(c.caminho(), 500))
                    .param("momento", utc(novo.momento()))
                    .param("fonte", novo.momento() == null ? "sem_horario" : "arquivo")
                    .param("criado", utc(novo.criadoEm()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public void apagar(UUID arquivoId) {
        jdbc.sql("DELETE FROM atendimento.arquivo WHERE id = :id AND origem <> 'anexo_conversa'").param("id", arquivoId).update();
    }

    @Override
    public void marcarRemovido(UUID arquivoId) {
        jdbc.sql("""
                        UPDATE atendimento.arquivo
                           SET situacao = 'removido', caminho = NULL, url_download = NULL, erro_codigo = NULL, erro_mensagem = NULL
                         WHERE id = :id
                        """)
                .param("id", arquivoId)
                .update();
    }

    @Override
    public void marcarTodosRemovidos(UUID atendimentoId) {
        jdbc.sql("""
                        UPDATE atendimento.arquivo
                           SET situacao = 'removido', caminho = NULL, url_download = NULL, erro_codigo = NULL, erro_mensagem = NULL
                         WHERE atendimento_id = :atendimento
                        """)
                .param("atendimento", atendimentoId)
                .update();
    }

    @Override
    public int renovarLinks(UUID atendimentoId, List<AnexoLido> anexosDoPdfNovo) {
        List<Faltando> faltando = jdbc.sql("""
                        SELECT id, ordem, nome_original FROM atendimento.arquivo
                         WHERE atendimento_id = :atendimento AND origem = 'anexo_conversa'
                           AND situacao IN ('aguardando', 'falhou', 'vencido')
                         ORDER BY ordem
                        """)
                .param("atendimento", atendimentoId)
                .query((rs, linha) -> new Faltando(rs.getObject("id", UUID.class), rs.getInt("ordem"), rs.getString("nome_original")))
                .list();
        Set<Integer> usados = new HashSet<>();
        int renovados = 0;
        for (Faltando f : faltando) {
            AnexoLido novo = correspondente(f, anexosDoPdfNovo, usados);
            if (novo == null || novo.url() == null) {
                continue;
            }
            usados.add(novo.ordem());
            renovados += jdbc.sql("""
                            UPDATE atendimento.arquivo
                               SET url_download = :url, url_valida_ate = :validoAte, situacao = 'aguardando',
                                   erro_codigo = NULL, erro_mensagem = NULL
                             WHERE id = :id AND situacao IN ('aguardando', 'falhou', 'vencido')
                               AND url_download IS DISTINCT FROM :url
                            """)
                    .param("id", f.id())
                    .param("url", novo.url())
                    .param("validoAte", utc(novo.validoAte()))
                    .update();
        }
        return renovados;
    }

    private record Faltando(UUID id, int ordem, String nome) {
    }

    /** O mesmo anexo no PDF novo: mesmo nome e mesma ordem; se a ordem mudou (o PDF tem mais mensagens), o mesmo nome. */
    private static AnexoLido correspondente(Faltando f, List<AnexoLido> novos, Set<Integer> usados) {
        AnexoLido mesmoNome = null;
        for (AnexoLido a : novos) {
            if (usados.contains(a.ordem()) || !a.nome().equals(f.nome())) {
                continue;
            }
            if (a.ordem() == f.ordem()) {
                return a;
            }
            if (mesmoNome == null) {
                mesmoNome = a;
            }
        }
        return mesmoNome;
    }

    private static Map<String, Object> conteudo(ConteudoGuardado c, String mensagemSeNaoSuportado) {
        Map<String, Object> p = new HashMap<>();
        p.put("situacao", c.suportado() ? SituacaoDoArquivo.PRONTO.codigo() : SituacaoDoArquivo.NAO_SUPORTADO.codigo());
        p.put("tamanho", c.tamanho());
        p.put("sha", c.sha256());
        p.put("caminho", cortar(c.caminho(), 500));
        p.put("categoria", c.categoria().codigo());
        p.put("tipo", cortar(c.tipoDeMidia(), 100));
        p.put("codigo", c.suportado() ? null : "TIPO_NAO_SUPORTADO");
        p.put("mensagem", c.suportado() ? null : cortar(mensagemSeNaoSuportado, 500));
        return p;
    }

    private static ArquivoDoAtendimento arquivo(ResultSet rs, int linha) throws SQLException {
        return new ArquivoDoAtendimento(rs.getObject("id", UUID.class), rs.getString("origem"), rs.getInt("ordem"),
                rs.getString("nome_original"), CategoriaDoArquivo.doCodigo(rs.getString("categoria")),
                rs.getString("situacao"), instante(rs, "url_valida_ate"), rs.getObject("mensagem_ordem", Integer.class),
                instante(rs, "momento"), rs.getObject("tamanho", Long.class), rs.getString("erro_codigo"),
                rs.getString("erro_mensagem"), rs.getString("caminho"));
    }

    private static ArquivoParaBaixar paraBaixar(ResultSet rs, int linha) throws SQLException {
        return new ArquivoParaBaixar(rs.getObject("id", UUID.class), rs.getObject("atendimento_id", UUID.class),
                rs.getInt("ordem"), rs.getString("nome_original"), rs.getString("url_download"),
                instante(rs, "url_valida_ate"), SituacaoDoArquivo.doCodigo(rs.getString("situacao")));
    }

    private static String cortar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

}
