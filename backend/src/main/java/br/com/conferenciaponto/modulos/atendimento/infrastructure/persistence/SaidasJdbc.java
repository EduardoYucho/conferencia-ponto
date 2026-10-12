package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.instante;
import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.utc;

/** As saídas em atendimento.saida e as imagens delas em atendimento.imagem. */
@Repository
class SaidasJdbc implements Saidas {

    private static final String COLUNAS = """
            s.id, s.atendimento_id, s.tipo, s.versao, s.situacao, s.erro_mensagem, s.estrutura::text AS estrutura, s.html,
            s.html_editado, s.assunto, s.modelo, s.versao_prompt, s.tokens_entrada, s.tokens_saida, s.criada_em""";

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    SaidasJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public Optional<SaidaGuardada> buscar(UUID saidaId) {
        return jdbc.sql("SELECT " + COLUNAS + " FROM atendimento.saida s WHERE s.id = :id")
                .param("id", saidaId)
                .query(SaidasJdbc::saida)
                .optional();
    }

    @Override
    public List<SaidaGuardada> ultimas(UUID atendimentoId) {
        return jdbc.sql("SELECT DISTINCT ON (s.tipo) " + COLUNAS + """
                         FROM atendimento.saida s
                        WHERE s.atendimento_id = :atendimento
                        ORDER BY s.tipo, s.versao DESC
                        """)
                .param("atendimento", atendimentoId)
                .query(SaidasJdbc::saida)
                .list()
                .stream()
                .sorted(Comparator.comparing(SaidaGuardada::tipo))
                .toList();
    }

    @Override
    public Optional<SaidaGuardada> ultima(UUID atendimentoId, TipoDeSaida tipo) {
        return jdbc.sql("SELECT " + COLUNAS + """
                         FROM atendimento.saida s
                        WHERE s.atendimento_id = :atendimento AND s.tipo = :tipo
                        ORDER BY s.versao DESC LIMIT 1
                        """)
                .param("atendimento", atendimentoId)
                .param("tipo", tipo.codigo())
                .query(SaidasJdbc::saida)
                .optional();
    }

    @Override
    public Optional<UUID> criar(UUID atendimentoId, TipoDeSaida tipo, Set<SituacaoDaSaida> seAUltimaFor, Instant agora) {
        return transacao.execute(status -> {
            // dois pedidos ao mesmo tempo não disputam a mesma versão nem criam duas
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:item, 0))")
                    .param("item", "saida:" + atendimentoId + ":" + tipo.codigo())
                    .query().singleRow();
            Optional<String> ultima = jdbc.sql("""
                            SELECT situacao FROM atendimento.saida
                             WHERE atendimento_id = :atendimento AND tipo = :tipo
                             ORDER BY versao DESC LIMIT 1
                            """)
                    .param("atendimento", atendimentoId)
                    .param("tipo", tipo.codigo())
                    .query(String.class)
                    .optional();
            if (ultima.isPresent() && !seAUltimaFor.contains(SituacaoDaSaida.doCodigo(ultima.get()))) {
                return Optional.<UUID>empty();
            }
            return Optional.of(jdbc.sql("""
                            INSERT INTO atendimento.saida (atendimento_id, tipo, versao, situacao, criada_em)
                            SELECT :atendimento, :tipo, coalesce(max(versao), 0) + 1, 'pendente', :agora
                              FROM atendimento.saida WHERE atendimento_id = :atendimento AND tipo = :tipo
                            RETURNING id
                            """)
                    .param("atendimento", atendimentoId)
                    .param("tipo", tipo.codigo())
                    .param("agora", utc(agora))
                    .query(UUID.class)
                    .single());
        });
    }

    @Override
    public List<UUID> esperando(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT id FROM atendimento.saida
                         WHERE atendimento_id = :atendimento AND situacao IN ('pendente', 'redigindo')
                         ORDER BY tipo, versao
                        """)
                .param("atendimento", atendimentoId)
                .query(UUID.class)
                .list();
    }

    @Override
    public boolean mudarSituacaoSe(UUID saidaId, Set<SituacaoDaSaida> de, SituacaoDaSaida nova, String erroMensagem) {
        if (de.isEmpty()) {
            return false;
        }
        return jdbc.sql("""
                        UPDATE atendimento.saida SET situacao = :situacao, erro_mensagem = :erro
                         WHERE id = :id AND situacao IN (:de)
                        """)
                .param("id", saidaId)
                .param("situacao", nova.codigo())
                .param("erro", limitar(erroMensagem, 500))
                .param("de", de.stream().map(SituacaoDaSaida::codigo).toList())
                .update() > 0;
    }

    @Override
    public int falharEsperando(UUID atendimentoId, String erroMensagem) {
        return jdbc.sql("""
                        UPDATE atendimento.saida SET situacao = 'falhou', erro_mensagem = :erro
                         WHERE atendimento_id = :atendimento AND situacao IN ('pendente', 'redigindo')
                        """)
                .param("atendimento", atendimentoId)
                .param("erro", limitar(erroMensagem, 500))
                .update();
    }

    @Override
    public boolean salvarRedacao(UUID saidaId, String estrutura, String html, String assunto, String modelo,
                                 int versaoPrompt, Integer tokensEntrada, Integer tokensSaida, List<NovaImagem> imagens) {
        Boolean salvou = transacao.execute(status -> {
            int mudou = jdbc.sql("""
                            UPDATE atendimento.saida
                               SET situacao = 'pronta', erro_mensagem = NULL, estrutura = CAST(:estrutura AS jsonb),
                                   html = :html, html_editado = NULL, assunto = :assunto, modelo = :modelo,
                                   versao_prompt = :versao, tokens_entrada = :entrada, tokens_saida = :saida
                             WHERE id = :id AND situacao IN ('pendente', 'redigindo')
                            """)
                    .param("id", saidaId)
                    .param("estrutura", estrutura)
                    .param("html", html)
                    .param("assunto", limitar(assunto, 150))
                    .param("modelo", limitar(modelo, 60))
                    .param("versao", (short) versaoPrompt)
                    .param("entrada", tokensEntrada)
                    .param("saida", tokensSaida)
                    .update();
            if (mudou == 0) {
                return false;
            }
            jdbc.sql("DELETE FROM atendimento.imagem WHERE saida_id = :saida").param("saida", saidaId).update();
            for (NovaImagem imagem : imagens) {
                jdbc.sql("""
                                INSERT INTO atendimento.imagem (id, saida_id, numero, arquivo_id, instante_video_ms, caminho,
                                                                legenda, incluida)
                                VALUES (:id, :saida, :numero, :arquivo, :instante, :caminho, :legenda, TRUE)
                                """)
                        .param("id", imagem.id())
                        .param("saida", saidaId)
                        .param("numero", (short) imagem.numero())
                        .param("arquivo", imagem.arquivoId())
                        .param("instante", imagem.instanteVideoMs() == null ? null
                                : (int) Math.min(Integer.MAX_VALUE, imagem.instanteVideoMs()), java.sql.Types.INTEGER)
                        .param("caminho", imagem.caminho())
                        .param("legenda", limitar(imagem.legenda(), 300))
                        .update();
            }
            return true;
        });
        return Boolean.TRUE.equals(salvou);
    }

    @Override
    public List<ImagemDaSaida> imagens(UUID saidaId) {
        return jdbc.sql("""
                        SELECT id, saida_id, numero, arquivo_id, caminho, legenda, incluida
                          FROM atendimento.imagem WHERE saida_id = :saida ORDER BY numero
                        """)
                .param("saida", saidaId)
                .query((rs, linha) -> new ImagemDaSaida(rs.getObject("id", UUID.class), rs.getObject("saida_id", UUID.class),
                        rs.getInt("numero"), rs.getObject("arquivo_id", UUID.class), rs.getString("caminho"),
                        rs.getString("legenda"), rs.getBoolean("incluida")))
                .list();
    }

    @Override
    public void salvarTexto(UUID saidaId, String htmlEditado, List<ImagemNumerada> ordem) {
        transacao.executeWithoutResult(status -> {
            // a unicidade do número só é conferida no fim: as imagens trocam de número entre si
            jdbc.sql("SET CONSTRAINTS atendimento.uk_atd_imagem_numero DEFERRED").update();
            for (ImagemNumerada imagem : ordem) {
                jdbc.sql("""
                                UPDATE atendimento.imagem SET numero = :numero, incluida = :incluida, legenda = :legenda
                                 WHERE id = :id AND saida_id = :saida
                                """)
                        .param("id", imagem.id())
                        .param("saida", saidaId)
                        .param("numero", (short) imagem.numero())
                        .param("incluida", imagem.incluida())
                        .param("legenda", limitar(imagem.legenda(), 300))
                        .update();
            }
            jdbc.sql("UPDATE atendimento.saida SET html_editado = :html WHERE id = :id")
                    .param("id", saidaId)
                    .param("html", htmlEditado)
                    .update();
        });
    }

    @Override
    public void salvarAssunto(UUID saidaId, String assunto) {
        jdbc.sql("UPDATE atendimento.saida SET assunto = :assunto WHERE id = :id")
                .param("id", saidaId)
                .param("assunto", limitar(assunto, 150))
                .update();
    }

    @Override
    public void salvarTela(UUID saidaId, String telaFuncionalidade) {
        jdbc.sql("""
                        UPDATE atendimento.saida
                           SET estrutura = jsonb_set(coalesce(estrutura, '{}'::jsonb), '{telaFuncionalidade}', coalesce(to_jsonb(CAST(:tela AS text)), 'null'::jsonb))
                         WHERE id = :id
                        """)
                .param("id", saidaId)
                .param("tela", limitar(telaFuncionalidade, 200))
                .update();
    }

    @Override
    public long[] tokens(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT coalesce(sum(tokens_entrada), 0) AS entrada, coalesce(sum(tokens_saida), 0) AS saida
                          FROM atendimento.saida WHERE atendimento_id = :atendimento
                        """)
                .param("atendimento", atendimentoId)
                .query((rs, linha) -> new long[]{rs.getLong("entrada"), rs.getLong("saida")})
                .single();
    }

    private static SaidaGuardada saida(ResultSet rs, int linha) throws SQLException {
        return new SaidaGuardada(rs.getObject("id", UUID.class), rs.getObject("atendimento_id", UUID.class),
                TipoDeSaida.doCodigo(rs.getString("tipo")).orElseThrow(), rs.getInt("versao"),
                SituacaoDaSaida.doCodigo(rs.getString("situacao")), rs.getString("erro_mensagem"), rs.getString("estrutura"),
                rs.getString("html"), rs.getString("html_editado"), rs.getString("assunto"), rs.getString("modelo"),
                rs.getObject("versao_prompt", Integer.class), rs.getObject("tokens_entrada", Integer.class),
                rs.getObject("tokens_saida", Integer.class), instante(rs, "criada_em"));
    }

    private static String limitar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
