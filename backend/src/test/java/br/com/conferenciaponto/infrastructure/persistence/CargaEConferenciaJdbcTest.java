package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V17 no PostgreSQL temporário: a carga semanal do horário, a conferência do mês e a origem das mudanças à mão. */
class CargaEConferenciaJdbcTest {

    private static final Instant AGORA = Instant.parse("2026-11-02T13:00:00Z");

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final HorarioTrabalhoRepositoryJdbc horarios = new HorarioTrabalhoRepositoryJdbc(jdbc);
    private final ConferenciaMesRepositoryJdbc conferencias = new ConferenciaMesRepositoryJdbc(jdbc);

    @Test
    @DisplayName("O horário guarda a carga semanal informada (vazia = a soma do horário) e o banco recusa fora de 1 a 80h")
    void cargaDoHorario() {
        UUID pessoa = PostgresDeTeste.novoUsuario(true);
        HorarioTrabalho semCarga = HorarioTrabalho.semanal(pessoa, HorarioTrabalho.DESDE_SEMPRE,
                GradeHoraria.ler("08:00-12:00 13:00-17:00"), 5, "teste", AGORA);
        HorarioTrabalho comCarga = HorarioTrabalho.semanal(pessoa, LocalDate.of(2026, 10, 1),
                GradeHoraria.ler("08:00-12:00 13:00-17:00"), 5, "teste", AGORA).comCarga(42 * 3600);
        horarios.salvar(semCarga);
        horarios.salvar(comCarga);

        assertThat(horarios.listarPorUsuario(pessoa)).extracting(HorarioTrabalho::cargaSemanalInformadaSegundos)
                .containsExactly(null, 42 * 3600);
        horarios.salvar(comCarga.comCarga(null));
        assertThat(horarios.buscarPorId(comCarga.id())).get()
                .satisfies(h -> assertThat(h.cargaSemanalSegundos()).isEqualTo(40 * 3600));

        assertThatThrownBy(() -> jdbc.sql("UPDATE tb_horario_trabalho SET carga_semanal_segundos = 100 WHERE id = ?")
                .param(comCarga.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Uma conferência que vale por mês; a reaberta fica no histórico e o mês pode ser conferido de novo")
    void conferencia() {
        UUID pessoa = PostgresDeTeste.novoUsuario(true);
        YearMonth outubro = YearMonth.of(2026, 10);
        SaldoMensal numeros = new SaldoMensal(2026, 10, 21, 0, 600_000, 590_000, -2_880, -2_880, 0, -17_280);

        ConferenciaMes primeira = ConferenciaMes.nova(pessoa, outubro, "carla", "Tudo certo", numeros, AGORA);
        conferencias.salvar(primeira);
        assertThat(conferencias.vigente(pessoa, outubro)).get().satisfies(c -> {
            assertThat(c.conferidoPor()).isEqualTo("carla");
            assertThat(c.observacao()).isEqualTo("Tudo certo");
            assertThat(c.saldoMesSegundos()).isEqualTo(-2_880);
            assertThat(c.trabalhadoSegundos()).isEqualTo(600_000);
            assertThat(c.diasRegistrados()).isEqualTo(21);
            assertThat(c.conferidoEm()).isEqualTo(AGORA.truncatedTo(ChronoUnit.MICROS));
            assertThat(c.vale()).isTrue();
        });
        assertThatThrownBy(() -> conferencias.salvar(ConferenciaMes.nova(pessoa, outubro, "ana", null, numeros, AGORA)))
                .as("duas conferências valendo no mesmo mês").isInstanceOf(DataIntegrityViolationException.class);

        conferencias.salvar(primeira.reaberta("carla", "Faltou o atestado", AGORA.plusSeconds(60)));
        assertThat(conferencias.vigente(pessoa, outubro)).isEmpty();
        conferencias.salvar(ConferenciaMes.nova(pessoa, outubro, "carla", null, numeros, AGORA.plusSeconds(120)));
        conferencias.salvar(ConferenciaMes.nova(pessoa, YearMonth.of(2026, 12), "carla", null, numeros, AGORA));

        assertThat(conferencias.historico(pessoa, outubro)).hasSize(2).first()
                .satisfies(c -> assertThat(c.vale()).isTrue());
        assertThat(conferencias.historico(pessoa, outubro).get(1).motivoReabertura()).isEqualTo("Faltou o atestado");
        assertThat(conferencias.vigentesDesde(pessoa, YearMonth.of(2026, 9))).extracting(ConferenciaMes::mes)
                .containsExactly(outubro, YearMonth.of(2026, 12));
        assertThat(conferencias.vigentesDesde(pessoa, YearMonth.of(2026, 11))).extracting(ConferenciaMes::mes)
                .containsExactly(YearMonth.of(2026, 12));
    }

    @Test
    @DisplayName("A origem de cada mudança à mão: AJUSTE por padrão (os registros antigos), RH, MANUAL ou EXCLUSAO")
    void origemDoAjuste() {
        UUID pessoa = PostgresDeTeste.novoUsuario(true);
        jdbc.sql("""
                INSERT INTO tb_ajuste_jornada (id, usuario_id, data_referencia, batidas_antes, batidas_depois, justificativa,
                    usuario_login) VALUES (?, ?, DATE '2026-10-17', '', '08:00:00 12:00:00', 'Lançamento manual', 'eduardo')""")
                .params(UUID.randomUUID(), pessoa).update();
        assertThat(jdbc.sql("SELECT origem FROM tb_ajuste_jornada WHERE usuario_id = ?").param(pessoa)
                .query(String.class).single()).isEqualTo("AJUSTE");
        assertThatThrownBy(() -> jdbc.sql("UPDATE tb_ajuste_jornada SET origem = 'OUTRA' WHERE usuario_id = ?")
                .param(pessoa).update()).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.sql("UPDATE tb_ajuste_jornada SET origem = 'MANUAL' WHERE usuario_id = ?").param(pessoa).update();
    }

    @Test
    @DisplayName("O perfil Coordenador ganhou ponto.conferir (e o histórico de acessos diz isso)")
    void permissaoNova() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM tb_perfil_permissao pp JOIN tb_perfil_acesso p ON p.id = pp.perfil_id
                 WHERE p.sistema = 'ROLE_VIEWER' AND pp.permissao = 'ponto.conferir'""").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM tb_historico_acesso WHERE descricao LIKE '%Conferir o mês%'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
