package br.com.conferenciaponto.modulos;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Um PostgreSQL temporário com todas as migrações, compartilhado pelos testes de repositório dos módulos (sobe
 * uma vez por execução dos testes, numa pasta temporária, e é apagado no fim). Como na máquina de produção, o
 * sistema entra como "ponto", dono do banco e sem ser superusuário. Cada teste cria os próprios usuários.
 */
public final class PostgresDeTeste {

    private static EmbeddedPostgres postgres;
    private static DataSource banco;

    private PostgresDeTeste() {
    }

    public static synchronized DataSource banco() {
        if (banco == null) {
            try {
                postgres = EmbeddedPostgres.builder().start();
                try (Connection conexao = postgres.getPostgresDatabase().getConnection();
                     Statement sql = conexao.createStatement()) {
                    sql.execute("CREATE ROLE ponto LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE");
                    sql.execute("CREATE DATABASE conferencia_ponto OWNER ponto ENCODING 'UTF8' TEMPLATE template0"
                            + " LC_COLLATE 'C' LC_CTYPE 'C'");
                }
                banco = postgres.getDatabase("ponto", "conferencia_ponto");
                Flyway.configure().dataSource(banco).locations("classpath:db/migration").load().migrate();
                Runtime.getRuntime().addShutdownHook(new Thread(PostgresDeTeste::parar));
            } catch (IOException e) {
                throw new UncheckedIOException("Não foi possível subir o PostgreSQL temporário", e);
            } catch (SQLException e) {
                throw new IllegalStateException("Não foi possível preparar o PostgreSQL temporário", e);
            }
        }
        return banco;
    }

    public static JdbcClient jdbc() {
        return JdbcClient.create(banco());
    }

    /** Um usuário novo em tb_usuario (login único). */
    public static UUID novoUsuario(boolean ativo) {
        String login = "teste" + UUID.randomUUID().toString().substring(0, 8);
        return jdbc().sql("INSERT INTO tb_usuario (login, nome, senha_hash, ativo) VALUES (?, 'Pessoa de teste', 'x', ?) RETURNING id")
                .params(login, ativo)
                .query(UUID.class).single();
    }

    private static void parar() {
        try {
            if (postgres != null) {
                postgres.close();
            }
        } catch (IOException e) {
            // fim da JVM: a pasta temporária é apagada de qualquer jeito
        }
    }
}
