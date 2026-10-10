package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ResumoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.TarefaDeAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.instante;
import static br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.AtendimentosJdbc.utc;

/** atendimento.analise, com o que a análise lê de atendimento.arquivo e de atendimento.tarefa. */
@Repository
class AnalisesJdbc implements Analises {

    private static final String ARQUIVO = """
            SELECT a.id, a.atendimento_id, t.usuario_id, a.origem, a.ordem, a.nome_original, a.categoria, a.tipo_midia,
                   a.tamanho, a.sha256, a.caminho, a.situacao, a.gemini_arquivo, a.gemini_expira_em
              FROM atendimento.arquivo a JOIN atendimento.atendimento t ON t.id = a.atendimento_id
            """;

    private static final String ANALISE = """
            SELECT id, arquivo_id, versao_prompt, modelo, resultado::text AS resultado, tokens_entrada, tokens_saida,
                   duracao_ms, criada_em
              FROM atendimento.analise
            """;

    private final JdbcClient jdbc;

    AnalisesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ArquivoParaAnalisar> arquivo(UUID arquivoId) {
        return jdbc.sql(ARQUIVO + " WHERE a.id = :id").param("id", arquivoId).query(AnalisesJdbc::arquivo).optional();
    }

    @Override
    public List<ArquivoParaAnalisar> arquivosDoAtendimento(UUID atendimentoId) {
        return jdbc.sql(ARQUIVO + " WHERE a.atendimento_id = :id ORDER BY CASE a.origem WHEN 'anexo_conversa' THEN 0"
                        + " WHEN 'ligacao' THEN 1 WHEN 'video' THEN 2 ELSE 3 END, a.ordem")
                .param("id", atendimentoId)
                .query(AnalisesJdbc::arquivo).list();
    }

    @Override
    public Optional<AnaliseGuardada> buscar(UUID arquivoId, int versaoPrompt, String modelo) {
        return jdbc.sql(ANALISE + " WHERE arquivo_id = :arquivo AND versao_prompt = :versao AND modelo = :modelo")
                .param("arquivo", arquivoId)
                .param("versao", (short) versaoPrompt)
                .param("modelo", modelo)
                .query(AnalisesJdbc::analise).optional();
    }

    @Override
    public Optional<AnaliseGuardada> maisRecente(UUID arquivoId) {
        return jdbc.sql(ANALISE + " WHERE arquivo_id = :arquivo ORDER BY criada_em DESC, versao_prompt DESC LIMIT 1")
                .param("arquivo", arquivoId)
                .query(AnalisesJdbc::analise).optional();
    }

    @Override
    public Optional<AnaliseGuardada> doMesmoConteudo(UUID usuarioId, String sha256, int versaoPrompt, String modelo,
                                                     UUID outroQue) {
        if (sha256 == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                        SELECT n.id, n.arquivo_id, n.versao_prompt, n.modelo, n.resultado::text AS resultado, n.tokens_entrada,
                               n.tokens_saida, n.duracao_ms, n.criada_em
                          FROM atendimento.analise n
                          JOIN atendimento.arquivo a ON a.id = n.arquivo_id
                          JOIN atendimento.atendimento t ON t.id = a.atendimento_id
                         WHERE t.usuario_id = :usuario AND a.sha256 = :sha AND a.id <> :outro
                           AND n.versao_prompt = :versao AND n.modelo = :modelo
                         ORDER BY n.criada_em DESC LIMIT 1
                        """)
                .param("usuario", usuarioId)
                .param("sha", sha256)
                .param("outro", outroQue)
                .param("versao", (short) versaoPrompt)
                .param("modelo", modelo)
                .query(AnalisesJdbc::analise).optional();
    }

    @Override
    public boolean salvar(UUID arquivoId, int versaoPrompt, String modelo, String resultadoJson, Integer tokensEntrada,
                          Integer tokensSaida, Integer duracaoMs, Instant agora) {
        return jdbc.sql("""
                        INSERT INTO atendimento.analise (arquivo_id, versao_prompt, modelo, resultado, tokens_entrada,
                                                         tokens_saida, duracao_ms, criada_em)
                        VALUES (:arquivo, :versao, :modelo, CAST(:resultado AS jsonb), :entrada, :saida, :duracao, :agora)
                        ON CONFLICT ON CONSTRAINT uk_atd_analise DO NOTHING
                        """)
                .param("arquivo", arquivoId)
                .param("versao", (short) versaoPrompt)
                .param("modelo", modelo)
                .param("resultado", resultadoJson)
                .param("entrada", tokensEntrada)
                .param("saida", tokensSaida)
                .param("duracao", duracaoMs)
                .param("agora", utc(agora))
                .update() > 0;
    }

    @Override
    public List<ResumoDaAnalise> doAtendimento(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT n.arquivo_id, n.versao_prompt, n.modelo, n.tokens_entrada, n.tokens_saida, n.criada_em
                          FROM atendimento.analise n JOIN atendimento.arquivo a ON a.id = n.arquivo_id
                         WHERE a.atendimento_id = :id
                         ORDER BY n.criada_em
                        """)
                .param("id", atendimentoId)
                .query((rs, i) -> new ResumoDaAnalise(rs.getObject("arquivo_id", UUID.class), rs.getShort("versao_prompt"),
                        rs.getString("modelo"), inteiro(rs, "tokens_entrada"), inteiro(rs, "tokens_saida"),
                        instante(rs, "criada_em")))
                .list();
    }

    @Override
    public List<TarefaDeAnalise> tarefasDoAtendimento(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (arquivo_id) arquivo_id, situacao, erro_codigo, erro_mensagem, tentativas, max_tentativas
                          FROM atendimento.tarefa
                         WHERE atendimento_id = :id AND tipo = 'analisar' AND arquivo_id IS NOT NULL
                         ORDER BY arquivo_id, id DESC
                        """)
                .param("id", atendimentoId)
                .query((rs, i) -> new TarefaDeAnalise(rs.getObject("arquivo_id", UUID.class), rs.getString("situacao"),
                        rs.getString("erro_codigo"), rs.getString("erro_mensagem"), rs.getShort("tentativas"),
                        rs.getShort("max_tentativas")))
                .list();
    }

    @Override
    public int apagarDoArquivo(UUID arquivoId) {
        return jdbc.sql("DELETE FROM atendimento.analise WHERE arquivo_id = :id").param("id", arquivoId).update();
    }

    @Override
    public void guardarArquivoRemoto(UUID arquivoId, String nome, Instant expiraEm) {
        jdbc.sql("UPDATE atendimento.arquivo SET gemini_arquivo = :nome, gemini_expira_em = :expira WHERE id = :id")
                .param("id", arquivoId)
                .param("nome", nome)
                .param("expira", expiraEm == null ? null : utc(expiraEm))
                .update();
    }

    @Override
    public void esquecerArquivoRemoto(UUID arquivoId) {
        jdbc.sql("UPDATE atendimento.arquivo SET gemini_arquivo = NULL, gemini_expira_em = NULL WHERE id = :id")
                .param("id", arquivoId)
                .update();
    }

    private static ArquivoParaAnalisar arquivo(ResultSet rs, int linha) throws SQLException {
        long lido = rs.getLong("tamanho");
        Long tamanho = rs.wasNull() ? null : lido;
        return new ArquivoParaAnalisar(rs.getObject("id", UUID.class), rs.getObject("atendimento_id", UUID.class),
                rs.getObject("usuario_id", UUID.class), rs.getString("origem"), rs.getInt("ordem"),
                rs.getString("nome_original"), CategoriaDoArquivo.doCodigo(rs.getString("categoria")), rs.getString("tipo_midia"),
                tamanho, rs.getString("sha256"), rs.getString("caminho"), rs.getString("situacao"),
                rs.getString("gemini_arquivo"), instante(rs, "gemini_expira_em"));
    }

    private static AnaliseGuardada analise(ResultSet rs, int linha) throws SQLException {
        return new AnaliseGuardada(rs.getObject("id", UUID.class), rs.getObject("arquivo_id", UUID.class),
                rs.getShort("versao_prompt"), rs.getString("modelo"), rs.getString("resultado"),
                inteiro(rs, "tokens_entrada"), inteiro(rs, "tokens_saida"), inteiro(rs, "duracao_ms"),
                instante(rs, "criada_em"));
    }

    private static Integer inteiro(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }
}
