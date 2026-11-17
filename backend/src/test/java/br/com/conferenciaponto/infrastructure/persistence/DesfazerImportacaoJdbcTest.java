package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V19 no PostgreSQL temporário: o mesmo PDF uma vez por pessoa, o status DESFEITO e a origem DESFEITO no histórico. */
class DesfazerImportacaoJdbcTest {

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();

    private void registro(UUID pessoa, String hash, String status) {
        jdbc.sql("""
                INSERT INTO tb_comprovante_ponto (id, usuario_id, nome_arquivo, hash_sha256, data_hora_batida, status)
                VALUES (?, ?, 'comprovante.pdf', ?, ?, ?)""")
                .params(UUID.randomUUID(), pessoa, hash, LocalDateTime.of(2026, 11, 3, 8, 0, 10), status).update();
    }

    @Test
    @DisplayName("O mesmo PDF pode ter um registro por pessoa (DESFEITO numa, IMPORTADO na outra), nunca dois na mesma")
    void umPorPessoa() {
        UUID ana = PostgresDeTeste.novoUsuario(true);
        UUID bia = PostgresDeTeste.novoUsuario(true);
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");

        registro(ana, hash, "DESFEITO");
        registro(bia, hash, "IMPORTADO");

        assertThat(jdbc.sql("SELECT count(*) FROM tb_comprovante_ponto WHERE hash_sha256 = ?").param(hash)
                .query(Long.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> registro(bia, hash, "DUPLICADO")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> registro(ana, UUID.randomUUID().toString(), "OUTRO"))
                .as("status fora da lista").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("O histórico aceita a origem DESFEITO")
    void origemNoHistorico() {
        UUID pessoa = PostgresDeTeste.novoUsuario(true);
        jdbc.sql("""
                INSERT INTO tb_ajuste_jornada (id, usuario_id, data_referencia, batidas_antes, batidas_depois,
                                               justificativa, usuario_login, origem)
                VALUES (?, ?, ?, '08:00:10 12:30:00', '08:00:10', 'Importação desfeita: é da Maria', 'eduardo', 'DESFEITO')""")
                .params(UUID.randomUUID(), pessoa, LocalDate.of(2026, 11, 3)).update();

        assertThat(jdbc.sql("SELECT origem FROM tb_ajuste_jornada WHERE usuario_id = ?").param(pessoa)
                .query(String.class).single()).isEqualTo("DESFEITO");
    }
}
