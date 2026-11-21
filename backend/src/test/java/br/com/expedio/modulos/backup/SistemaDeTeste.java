package br.com.expedio.modulos.backup;

import br.com.expedio.ExpedioApplication;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * Sobe o sistema inteiro (API, telas e os módulos) num PostgreSQL temporário, para testar os Backups de clientes de
 * ponta a ponta sem tocar no banco nem no serviço instalados. Não é um teste JUnit: roda pelo {@code main}.
 *
 * <pre>
 * java -cp &lt;classpath dos testes&gt; br.com.expedio.modulos.backup.SistemaDeTeste &lt;pasta temporária&gt;
 * </pre>
 *
 * Tudo vai para a pasta temporária ({@code user.home} aponta para lá: logs, comprovantes, backups); o banco é apagado
 * ao sair. Portas: 18080 (sistema) e 18086 (a pública dos backups). Login: {@code admin} / {@code senha-de-teste}
 * (Administrador). Desligados: backup diário, importação de PDFs, monitor fiscal e o túnel (ligue com o segundo
 * argumento {@code com-tunel}). Para as telas, copie o
 * {@code frontend/dist} para {@code target/classes/static} antes.
 */
public final class SistemaDeTeste {

    public static final String LOGIN = "admin";
    public static final String SENHA = "senha-de-teste";

    private SistemaDeTeste() {
    }

    public static void main(String[] argumentos) throws Exception {
        Path pasta = Path.of(argumentos.length > 0 ? argumentos[0] : System.getProperty("java.io.tmpdir"))
                .resolve("sistema-de-teste").toAbsolutePath();
        Files.createDirectories(pasta.resolve("home"));
        EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                postgres.close();
            } catch (Exception e) {
                // saindo
            }
        }));
        try (Connection conexao = postgres.getPostgresDatabase().getConnection(); Statement sql = conexao.createStatement()) {
            sql.execute("CREATE ROLE expedio LOGIN PASSWORD 'expedio' NOSUPERUSER NOCREATEDB NOCREATEROLE");
            sql.execute("CREATE DATABASE expedio OWNER expedio ENCODING 'UTF8' TEMPLATE template0 LC_COLLATE 'C' LC_CTYPE 'C'");
        }
        System.setProperty("user.home", pasta.resolve("home").toString());
        ConfigurableApplicationContext contexto = SpringApplication.run(ExpedioApplication.class,
                "--server.port=18080",
                "--spring.datasource.url=jdbc:postgresql://localhost:" + postgres.getPort() + "/expedio",
                "--spring.datasource.username=expedio",
                "--spring.datasource.password=expedio",
                "--expedio.backup.automatico=false",
                "--expedio.importacao-pdf.habilitado=false",
                "--fiscal.coleta.ativa=false",
                "--backup-clientes.porta-publica=18086",
                // segundo argumento "com-tunel": abre um túnel rápido de verdade (precisa do cloudflared)
                "--backup-clientes.tunel-ativo=" + (argumentos.length > 1 && "com-tunel".equals(argumentos[1])),
                "--backup-clientes.tamanho-bloco-mib=1",
                "--backup-clientes.nivel-zstd=3");
        JdbcClient jdbc = JdbcClient.create(contexto.getBean(DataSource.class));
        // as migrações já criam o "admin": só a senha conhecida, sem troca obrigatória, e o perfil Administrador
        UUID id = jdbc.sql("""
                        INSERT INTO tb_usuario (login, nome, senha_hash, ativo) VALUES (?, 'Administrador de teste', ?, TRUE)
                        ON CONFLICT (login) DO UPDATE SET senha_hash = EXCLUDED.senha_hash, ativo = TRUE
                        RETURNING id""")
                .params(LOGIN, new BCryptPasswordEncoder().encode(SENHA)).query(UUID.class).single();
        jdbc.sql("UPDATE tb_usuario SET trocar_senha = FALSE WHERE id = ?").param(id).update();
        jdbc.sql("""
                        INSERT INTO tb_usuario_perfil (usuario_id, perfil_id)
                        SELECT ?, id FROM tb_perfil_acesso WHERE sistema = 'ROLE_ADMIN' ON CONFLICT DO NOTHING""")
                .param(id).update();
        System.out.println("SISTEMA DE TESTE PRONTO em " + pasta);
    }
}
