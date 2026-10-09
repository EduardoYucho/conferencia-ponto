package br.com.conferenciaponto.modulos;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migrações dos módulos (V14 e V15) num PostgreSQL de verdade, temporário: sobe numa pasta temporária só durante
 * o teste e é apagado no fim (nunca toca no banco do sistema). Como na máquina de produção, o sistema entra como
 * "ponto", dono do banco e sem ser superusuário.
 */
class MigracoesPostgresTest {

    private static EmbeddedPostgres postgres;
    private static DataSource banco;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transacao;
    private static List<String> pontoDepoisDaV13;

    @BeforeAll
    static void subirOBancoEAplicarAsMigracoes() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        try (Connection conexao = postgres.getPostgresDatabase().getConnection();
             Statement sql = conexao.createStatement()) {
            sql.execute("CREATE ROLE ponto LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
            sql.execute("CREATE DATABASE conferencia_ponto OWNER ponto ENCODING 'UTF8' TEMPLATE template0"
                    + " LC_COLLATE 'C' LC_CTYPE 'C'");
        }
        banco = postgres.getDatabase("ponto", "conferencia_ponto");
        jdbc = new JdbcTemplate(banco);
        transacao = new TransactionTemplate(new DataSourceTransactionManager(banco));

        flyway().target("13").load().migrate();
        pontoDepoisDaV13 = estruturaDoPonto();
        flyway().load().migrate();
    }

    @AfterAll
    static void apagarOBanco() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void asMigracoesDosModulosForamAplicadas() {
        MigrationInfo[] aplicadas = flyway().load().info().applied();

        assertThat(aplicadas).allMatch(m -> m.getState() == MigrationState.SUCCESS);
        assertThat(Arrays.stream(aplicadas).map(m -> m.getVersion().getVersion())).contains("14", "15");
        assertThat(flyway().load().info().pending()).isEmpty();
    }

    @Test
    void nadaDoPontoMudou() {
        assertThat(estruturaDoPonto()).containsExactlyElementsOf(pontoDepoisDaV13);
    }

    @Test
    void asTabelasDosModulosSaoDoUsuarioDoSistema() {
        List<String> tabelas = jdbc.queryForList("""
                SELECT schemaname || '.' || tablename || ' ' || tableowner
                  FROM pg_tables WHERE schemaname IN ('atendimento', 'conhecimento')""", String.class);

        assertThat(tabelas).containsExactlyInAnyOrder(
                "atendimento.acesso ponto", "atendimento.chave_gemini ponto", "atendimento.atendimento ponto",
                "atendimento.arquivo ponto", "atendimento.analise ponto", "atendimento.saida ponto",
                "atendimento.imagem ponto", "atendimento.tarefa ponto",
                "conhecimento.acesso ponto", "conhecimento.registro ponto", "conhecimento.registro_imagem ponto",
                "conhecimento.registro_situacao ponto", "conhecimento.vetor ponto");
    }

    @Test
    void valoresForaDasRegrasSaoRecusados() {
        UUID usuario = novoUsuario();
        UUID atendimento = novoAtendimento(usuario);

        recusado("""
                INSERT INTO atendimento.atendimento (usuario_id, chamado_digisac, conversa, versao_leitor,
                       apagar_arquivos_em, apagar_textos_em, situacao)
                VALUES (?, '1', '{}'::jsonb, 1, now(), now(), 'qualquer')""", "ck_atd_atendimento_situacao", usuario);
        recusado("""
                INSERT INTO atendimento.atendimento (usuario_id, chamado_digisac, conversa, versao_leitor,
                       apagar_arquivos_em, apagar_textos_em)
                VALUES (?, '1', '[]'::jsonb, 1, now(), now())""", "ck_atd_atendimento_conversa", usuario);
        recusado("INSERT INTO atendimento.arquivo (atendimento_id, origem, ordem, nome_original) VALUES (?, 'email', 1, 'a')",
                "ck_atd_arquivo_origem", atendimento);
        recusado("INSERT INTO atendimento.saida (atendimento_id, tipo, situacao) VALUES (?, 'resumo', 'confirmada')",
                "ck_atd_saida_confirmada", atendimento);
        recusado("INSERT INTO atendimento.tarefa (atendimento_id, tipo, situacao) VALUES (?, 'baixar', 'executando')",
                "ck_atd_tarefa_execucao", atendimento);
        recusado("INSERT INTO atendimento.chave_gemini (usuario_id, cifrada, vetor_inicial, ultimos_caracteres) VALUES (?, ?, ?, 'abcd')",
                "ck_atd_chave_gemini_vetor", usuario, new byte[] {1}, new byte[8]);
        recusado("INSERT INTO conhecimento.acesso (usuario_id, pesquisar, curar) VALUES (?, false, true)",
                "ck_con_acesso_curar", usuario);
        recusado("""
                INSERT INTO conhecimento.registro (origem, autor_id, tipo, titulo, situacao)
                VALUES ('gerador', ?, 'erro', 'Sem saída', 'enviado_desenvolvimento')""", "ck_con_registro_gerado", usuario);
    }

    @Test
    void vetorPrecisaTerOTamanhoDaDimensao() {
        UUID registro = novoRegistro(novoUsuario(), "Registro do vetor", null, null, null);
        String sql = """
                INSERT INTO conhecimento.vetor (registro_id, modelo, dimensao, valores, texto_sha256)
                VALUES (?, ?, 3, ?::real[], ?)""";

        recusado(sql, "ck_con_vetor_valores", registro, "modelo-a", "{0.1,0.2}", "a".repeat(64));
        jdbc.update(sql, registro, "modelo-b", "{0.1,0.2,0.3}", "b".repeat(64));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM conhecimento.vetor WHERE registro_id = ?", Integer.class, registro))
                .isEqualTo(1);
    }

    @Test
    void aBuscaIgnoraAcentoEAcompanhaAEdicao() {
        UUID id = novoRegistro(novoUsuario(), "Rejeição na emissão da nota fiscal", "EQR76",
                "Duplicidade de NF-e", "Cliente Exemplo");

        assertThat(buscar("emissao")).contains("Rejeição na emissão da nota fiscal");
        assertThat(buscar("REJEICAO nota")).contains("Rejeição na emissão da nota fiscal");
        assertThat(buscar("duplicidade")).contains("Rejeição na emissão da nota fiscal");

        jdbc.update("UPDATE conhecimento.registro SET titulo = 'Falha ao imprimir o pedido' WHERE id = ?", id);

        assertThat(buscar("imprimir")).contains("Falha ao imprimir o pedido");
        assertThat(buscar("emissao")).doesNotContain("Falha ao imprimir o pedido");
    }

    @Test
    void codigoDaTelaPelaMetadeEMensagemDeErroComUmaPalavraDiferente() {
        novoRegistro(novoUsuario(), "Busca por trigramas", "EQR76", "Não foi possível gravar o orçamento", "Comércio Teste");

        assertThat(jdbc.queryForList("SELECT titulo FROM conhecimento.registro WHERE tela_codigo ILIKE ?",
                String.class, "%eqr7%")).contains("Busca por trigramas");
        assertThat(jdbc.queryForList("""
                SELECT titulo FROM conhecimento.registro
                 WHERE conhecimento.sem_acento(mensagem_erro) % conhecimento.sem_acento(?)""",
                String.class, "Nao foi possivel salvar o orcamento")).contains("Busca por trigramas");
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'conhecimento' AND indexdef LIKE '%gin_trgm_ops%'""", String.class))
                .containsExactlyInAnyOrder("ix_con_registro_tela_codigo", "ix_con_registro_mensagem_erro", "ix_con_registro_cliente");
    }

    @Test
    void apagarUmUsuarioApagaOsAcessosEAChaveDele() {
        UUID usuario = novoUsuario();
        jdbc.update("INSERT INTO atendimento.acesso (usuario_id, gerador) VALUES (?, true)", usuario);
        jdbc.update("INSERT INTO conhecimento.acesso (usuario_id, pesquisar, curar) VALUES (?, true, true)", usuario);
        jdbc.update("INSERT INTO atendimento.chave_gemini (usuario_id, cifrada, vetor_inicial, ultimos_caracteres) VALUES (?, ?, ?, 'abcd')",
                usuario, new byte[] {1, 2, 3}, new byte[12]);

        jdbc.update("DELETE FROM tb_usuario WHERE id = ?", usuario);

        for (String tabela : List.of("atendimento.acesso", "conhecimento.acesso", "atendimento.chave_gemini")) {
            assertThat(contar(tabela, "usuario_id", usuario)).as(tabela).isZero();
        }
    }

    @Test
    void usuarioComAtendimentoOuRegistroNaoPodeSerApagado() {
        UUID comAtendimento = novoUsuario();
        novoAtendimento(comAtendimento);
        UUID comRegistro = novoUsuario();
        novoRegistro(comRegistro, "Registro de quem não pode ser apagado", null, null, null);

        recusado("DELETE FROM tb_usuario WHERE id = ?", "fk_atd_atendimento_usuario", comAtendimento);
        recusado("DELETE FROM tb_usuario WHERE id = ?", "fk_con_registro_autor", comRegistro);
    }

    @Test
    void apagarOAtendimentoApagaTudoQueEDele() {
        UUID atendimento = novoAtendimento(novoUsuario());
        UUID arquivo = jdbc.queryForObject("""
                INSERT INTO atendimento.arquivo (atendimento_id, origem, ordem, nome_original, categoria)
                VALUES (?, 'anexo_conversa', 1, 'tela.jpeg', 'imagem') RETURNING id""", UUID.class, atendimento);
        jdbc.update("INSERT INTO atendimento.analise (arquivo_id, versao_prompt, modelo, resultado) VALUES (?, 1, 'modelo', '{}'::jsonb)",
                arquivo);
        UUID saida = jdbc.queryForObject("INSERT INTO atendimento.saida (atendimento_id, tipo) VALUES (?, 'resumo') RETURNING id",
                UUID.class, atendimento);
        jdbc.update("INSERT INTO atendimento.imagem (saida_id, numero, arquivo_id, caminho) VALUES (?, 1, ?, 'Imagem_01.jpeg')",
                saida, arquivo);
        jdbc.update("INSERT INTO atendimento.tarefa (atendimento_id, arquivo_id, tipo) VALUES (?, ?, 'analisar')", atendimento, arquivo);

        jdbc.update("DELETE FROM atendimento.atendimento WHERE id = ?", atendimento);

        assertThat(contar("atendimento.arquivo", "atendimento_id", atendimento)).isZero();
        assertThat(contar("atendimento.analise", "arquivo_id", arquivo)).isZero();
        assertThat(contar("atendimento.saida", "atendimento_id", atendimento)).isZero();
        assertThat(contar("atendimento.imagem", "saida_id", saida)).isZero();
        assertThat(contar("atendimento.tarefa", "atendimento_id", atendimento)).isZero();
    }

    @Test
    void osNumerosDasImagensPodemSerTrocadosNumaTransacao() {
        UUID saida = jdbc.queryForObject("INSERT INTO atendimento.saida (atendimento_id, tipo) VALUES (?, 'resumo') RETURNING id",
                UUID.class, novoAtendimento(novoUsuario()));
        String inserir = "INSERT INTO atendimento.imagem (saida_id, numero, caminho) VALUES (?, ?, ?) RETURNING id";
        UUID primeira = jdbc.queryForObject(inserir, UUID.class, saida, 1, "a.png");
        UUID segunda = jdbc.queryForObject(inserir, UUID.class, saida, 2, "b.png");

        recusado("UPDATE atendimento.imagem SET numero = 2 WHERE id = ?", "uk_atd_imagem_numero", primeira);
        transacao.executeWithoutResult(t -> {
            jdbc.execute("SET CONSTRAINTS atendimento.uk_atd_imagem_numero DEFERRED");
            jdbc.update("UPDATE atendimento.imagem SET numero = 2 WHERE id = ?", primeira);
            jdbc.update("UPDATE atendimento.imagem SET numero = 1 WHERE id = ?", segunda);
        });

        assertThat(jdbc.queryForObject("SELECT caminho FROM atendimento.imagem WHERE saida_id = ? AND numero = 1",
                String.class, saida)).isEqualTo("b.png");
    }

    // ----------------------------------------------------------------------------------------- apoio

    private static FluentConfiguration flyway() {
        return Flyway.configure().dataSource(banco).locations("classpath:db/migration");
    }

    /** Tabelas, colunas, restrições, índices, gatilhos e visões do ponto (schema public). */
    private static List<String> estruturaDoPonto() {
        return jdbc.queryForList("""
                SELECT 'coluna ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
                       || ' ' || coalesce(column_default, '')
                  FROM information_schema.columns WHERE table_schema = 'public'
                UNION ALL
                SELECT 'restricao ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
                  FROM pg_constraint WHERE connamespace = 'public'::regnamespace
                UNION ALL
                SELECT 'indice ' || indexdef FROM pg_indexes WHERE schemaname = 'public'
                UNION ALL
                SELECT 'gatilho ' || event_object_table || ' ' || trigger_name || ' ' || action_statement
                  FROM information_schema.triggers WHERE trigger_schema = 'public'
                UNION ALL
                SELECT 'visao ' || viewname || ' ' || definition FROM pg_views WHERE schemaname = 'public'
                ORDER BY 1""", String.class);
    }

    private static UUID novoUsuario() {
        String login = "teste" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc.queryForObject("INSERT INTO tb_usuario (login, nome, senha_hash) VALUES (?, 'Pessoa de teste', 'x') RETURNING id",
                UUID.class, login);
    }

    private static UUID novoAtendimento(UUID usuario) {
        return jdbc.queryForObject("""
                INSERT INTO atendimento.atendimento (usuario_id, chamado_digisac, conversa, versao_leitor,
                       apagar_arquivos_em, apagar_textos_em)
                VALUES (?, '12345', '{"mensagens": []}'::jsonb, 1, now() + interval '30 days', now() + interval '180 days')
                RETURNING id""", UUID.class, usuario);
    }

    private static UUID novoRegistro(UUID autor, String titulo, String telaCodigo, String mensagemErro, String cliente) {
        return jdbc.queryForObject("""
                INSERT INTO conhecimento.registro (origem, autor_id, tipo, titulo, tela_codigo, mensagem_erro, cliente, situacao)
                VALUES ('importado', ?, 'erro', ?, ?, ?, ?, 'enviado_desenvolvimento') RETURNING id""",
                UUID.class, autor, titulo, telaCodigo, mensagemErro, cliente);
    }

    private static List<String> buscar(String termos) {
        return jdbc.queryForList("""
                SELECT titulo FROM conhecimento.registro
                 WHERE busca @@ plainto_tsquery('pg_catalog.portuguese', conhecimento.sem_acento(?))""",
                String.class, termos);
    }

    private static int contar(String tabela, String coluna, UUID valor) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, valor);
    }

    private static void recusado(String sql, String restricao, Object... parametros) {
        assertThatThrownBy(() -> jdbc.update(sql, parametros))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(restricao);
    }
}
