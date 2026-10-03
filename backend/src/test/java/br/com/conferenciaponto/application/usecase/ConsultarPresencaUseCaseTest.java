package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.view.PresencaView;
import br.com.conferenciaponto.application.view.PresencaView.Pessoa;
import br.com.conferenciaponto.application.view.PresencaView.Situacao;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsultarPresencaUseCaseTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    /** Sexta-feira: dia de expediente no horário padrão (08:00–12:00 · 13:00–17:48). */
    private static final LocalDate SEXTA = LocalDate.of(2026, 10, 2);
    private static final LocalDate SABADO = SEXTA.plusDays(1);

    private static final Usuario ADMIN = usuario("00000000-0000-0000-0000-0000000000a1", "ana", "Ana", Perfil.ROLE_ADMIN, true);
    private static final Usuario BRUNO = usuario("00000000-0000-0000-0000-0000000000b2", "bruno", "Bruno", Perfil.ROLE_USER, true);
    private static final Usuario CARLA = usuario("00000000-0000-0000-0000-0000000000c3", "carla", "Carla", Perfil.ROLE_USER, true);
    private static final Usuario COORDENACAO = usuario("00000000-0000-0000-0000-0000000000d4", "coord", "Coordenação", Perfil.ROLE_VIEWER, true);
    private static final Usuario DESATIVADO = usuario("00000000-0000-0000-0000-0000000000e5", "dario", "Dario", Perfil.ROLE_USER, false);

    private final UsuarioRepositoryEmMemoria usuarios = new UsuarioRepositoryEmMemoria();
    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final AusenciaRepositoryEmMemoria ausencias = new AusenciaRepositoryEmMemoria();
    private final CalendarioFeriadosEmMemoria feriados = new CalendarioFeriadosEmMemoria();
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(feriados, ausencias));

    ConsultarPresencaUseCaseTest() {
        List.of(ADMIN, BRUNO, CARLA, COORDENACAO, DESATIVADO).forEach(usuarios::salvar);
    }

    private static Usuario usuario(String id, String login, String nome, Perfil perfil, boolean ativo) {
        return new Usuario(UUID.fromString(id), login, nome, "h:senha", ativo, Set.of(perfil), null);
    }

    private PresencaView painel(Usuario quem, LocalDate dia, String hora) {
        Instant instante = LocalDateTime.of(dia, LocalTime.parse(hora)).atZone(FUSO).toInstant();
        return new ConsultarPresencaUseCase(usuarios, registros, ausencias, feriados, regras, Clock.fixed(instante, FUSO))
                .agora(quem);
    }

    private static Pessoa de(PresencaView painel, Usuario usuario) {
        return painel.pessoas().stream().filter(p -> p.id().equals(usuario.id())).findFirst().orElseThrow();
    }

    private void bater(Usuario usuario, LocalDate dia, String... horarios) {
        RegistroJornada registro = RegistroJornada.novo(usuario.id(), dia, TipoDia.UTIL);
        for (String horario : horarios) {
            registro.registrarBatida(LocalTime.parse(horario), regras.motor(usuario.id(), dia));
        }
        registros.salvar(registro);
    }

    private void ausencia(Usuario usuario, TipoAusencia tipo, LocalDate inicio, LocalDate fim, String descricao) {
        ausencias.salvar(Ausencia.nova(usuario.id(), inicio, fim, tipo, descricao, "ana", Instant.EPOCH));
    }

    @Test
    @DisplayName("Entrada sem saída: está trabalhando (online) desde a batida")
    void trabalhando() {
        bater(BRUNO, SEXTA, "08:02");

        Pessoa bruno = de(painel(ADMIN, SEXTA, "10:00"), BRUNO);

        assertThat(bruno.situacao()).isEqualTo(Situacao.TRABALHANDO);
        assertThat(bruno.online()).isTrue();
        assertThat(bruno.desde()).isEqualTo(LocalTime.of(8, 2));
        assertThat(bruno.motivo()).isEqualTo("Trabalhando desde 08:02");
        assertThat(bruno.horario()).isEqualTo("08:00–12:00 · 13:00–17:48");
        assertThat(bruno.alemDoHorario()).isFalse();
        assertThat(bruno.atencao()).isFalse();
    }

    @Test
    @DisplayName("Saiu e o horário ainda tem período pela frente: em intervalo; depois do último, encerrou")
    void intervaloEEncerramento() {
        bater(BRUNO, SEXTA, "08:02", "12:01");
        Pessoa noAlmoco = de(painel(ADMIN, SEXTA, "12:30"), BRUNO);
        assertThat(noAlmoco.situacao()).isEqualTo(Situacao.INTERVALO);
        assertThat(noAlmoco.online()).isFalse();
        assertThat(noAlmoco.motivo()).isEqualTo("Em intervalo desde 12:01");

        // voltou do almoço: online de novo
        bater(BRUNO, SEXTA, "08:02", "12:01", "13:00");
        assertThat(de(painel(ADMIN, SEXTA, "15:00"), BRUNO).situacao()).isEqualTo(Situacao.TRABALHANDO);

        bater(BRUNO, SEXTA, "08:02", "12:01", "13:00", "17:50");
        Pessoa noFim = de(painel(ADMIN, SEXTA, "18:00"), BRUNO);
        assertThat(noFim.situacao()).isEqualTo(Situacao.ENCERROU);
        assertThat(noFim.motivo()).isEqualTo("Encerrou o expediente às 17:50");

        // saiu às 12:01 e não voltou: depois do fim do horário não é mais "intervalo"
        bater(CARLA, SEXTA, "08:00", "12:01");
        assertThat(de(painel(ADMIN, SEXTA, "18:00"), CARLA).situacao()).isEqualTo(Situacao.ENCERROU);
    }

    @Test
    @DisplayName("Sem batida: antes do horário, durante (pede atenção) e depois do horário")
    void semBatida() {
        Pessoa cedo = de(painel(ADMIN, SEXTA, "07:10"), BRUNO);
        assertThat(cedo.situacao()).isEqualTo(Situacao.ANTES_DO_EXPEDIENTE);
        assertThat(cedo.motivo()).isEqualTo("O expediente começa às 08:00");
        assertThat(cedo.atencao()).isFalse();

        Pessoa durante = de(painel(ADMIN, SEXTA, "10:00"), BRUNO);
        assertThat(durante.situacao()).isEqualTo(Situacao.SEM_BATIDA);
        assertThat(durante.motivo()).contains("Ainda não bateu o ponto").contains("08:00");
        assertThat(durante.atencao()).isTrue();
        assertThat(durante.online()).isFalse();

        Pessoa depois = de(painel(ADMIN, SEXTA, "19:00"), BRUNO);
        assertThat(depois.situacao()).isEqualTo(Situacao.NAO_REGISTROU);
    }

    @Test
    @DisplayName("Dia marcado como férias ou folga deixa a pessoa offline mesmo com batida, e diz até quando")
    void ausenciaVenceABatida() {
        bater(BRUNO, SEXTA, "08:02");
        ausencia(BRUNO, TipoAusencia.FERIAS, SEXTA.minusDays(3), SEXTA.plusDays(13), null);
        ausencia(CARLA, TipoAusencia.FOLGA, SEXTA, SEXTA, "Folga de aniversário");

        PresencaView painel = painel(CARLA, SEXTA, "10:00");

        Pessoa bruno = de(painel, BRUNO);
        assertThat(bruno.situacao()).isEqualTo(Situacao.AUSENCIA);
        assertThat(bruno.online()).isFalse();
        assertThat(bruno.motivo()).isEqualTo("Férias até 15/10");
        assertThat(de(painel, CARLA).motivo()).isEqualTo("Folga");
    }

    @Test
    @DisplayName("Atestado e abono: colegas veem só 'Ausência justificada'; administração, coordenação e a própria pessoa veem o motivo")
    void motivoReservado() {
        ausencia(BRUNO, TipoAusencia.ATESTADO, SEXTA, SEXTA.plusDays(1), "Consulta");
        ausencia(ADMIN, TipoAusencia.ABONO, SEXTA, SEXTA, "Doação de sangue");

        Pessoa paraColega = de(painel(CARLA, SEXTA, "10:00"), BRUNO);
        assertThat(paraColega.motivo()).isEqualTo("Ausência justificada até 03/10");
        assertThat(paraColega.detalhe()).isNull();
        assertThat(de(painel(CARLA, SEXTA, "10:00"), ADMIN).motivo()).isEqualTo("Ausência justificada");

        for (Usuario quem : List.of(ADMIN, COORDENACAO, BRUNO)) {
            Pessoa completo = de(painel(quem, SEXTA, "10:00"), BRUNO);
            assertThat(completo.motivo()).as("visto por %s", quem.login()).isEqualTo("Atestado até 03/10");
            assertThat(completo.detalhe()).isEqualTo("Consulta");
        }
    }

    @Test
    @DisplayName("Colegas não recebem login nem batidas dos outros; recebem os próprios")
    void detalheReservado() {
        bater(BRUNO, SEXTA, "08:02");
        bater(CARLA, SEXTA, "08:10");

        PresencaView paraCarla = painel(CARLA, SEXTA, "10:00");
        Pessoa bruno = de(paraCarla, BRUNO);
        assertThat(bruno.login()).isNull();
        assertThat(bruno.batidas()).isEmpty();
        assertThat(bruno.euMesmo()).isFalse();
        assertThat(bruno.desde()).isEqualTo(LocalTime.of(8, 2)); // "desde quando" todos veem
        Pessoa carla = de(paraCarla, CARLA);
        assertThat(carla.euMesmo()).isTrue();
        assertThat(carla.login()).isEqualTo("carla");
        assertThat(carla.batidas()).containsExactly(LocalTime.of(8, 10));

        Pessoa paraCoordenacao = de(painel(COORDENACAO, SEXTA, "10:00"), BRUNO);
        assertThat(paraCoordenacao.login()).isEqualTo("bruno");
        assertThat(paraCoordenacao.batidas()).containsExactly(LocalTime.of(8, 2));
    }

    @Test
    @DisplayName("Feriado e dia sem expediente: offline com o motivo; quem bate o ponto mesmo assim aparece trabalhando")
    void feriadoEFimDeSemana() {
        feriados.salvar(new Feriado(SEXTA, "Aniversário da cidade"));
        bater(CARLA, SEXTA, "09:00");

        PresencaView noFeriado = painel(ADMIN, SEXTA, "10:00");
        assertThat(de(noFeriado, BRUNO).situacao()).isEqualTo(Situacao.FERIADO);
        assertThat(de(noFeriado, BRUNO).motivo()).isEqualTo("Feriado: Aniversário da cidade");
        assertThat(de(noFeriado, CARLA).situacao()).isEqualTo(Situacao.TRABALHANDO);
        assertThat(de(noFeriado, CARLA).detalhe()).contains("feriado");

        bater(CARLA, SABADO, "09:00");
        PresencaView noSabado = painel(ADMIN, SABADO, "10:00");
        assertThat(de(noSabado, BRUNO).situacao()).isEqualTo(Situacao.SEM_EXPEDIENTE);
        assertThat(de(noSabado, BRUNO).horario()).isNull();
        assertThat(de(noSabado, CARLA).situacao()).isEqualTo(Situacao.TRABALHANDO);
        assertThat(de(noSabado, CARLA).alemDoHorario()).isTrue();
    }

    @Test
    @DisplayName("Todos os usuários ativos aparecem: online primeiro, coordenação por último, desativados fora")
    void todosAparecem() {
        bater(CARLA, SEXTA, "08:00");
        bater(BRUNO, SEXTA, "08:00", "12:00");

        PresencaView painel = painel(ADMIN, SEXTA, "12:30");

        assertThat(painel.pessoas()).extracting(Pessoa::nome).containsExactly("Carla", "Bruno", "Ana", "Coordenação");
        assertThat(painel.online()).isEqualTo(1);
        assertThat(painel.emIntervalo()).isEqualTo(1);
        Pessoa coordenacao = de(painel, COORDENACAO);
        assertThat(coordenacao.situacao()).isEqualTo(Situacao.NAO_REGISTRA_PONTO);
        assertThat(coordenacao.online()).isFalse();
        assertThat(painel.hoje()).isEqualTo(SEXTA);
        assertThat(painel.agora()).isEqualTo(LocalTime.of(12, 30));
    }

    @Test
    @DisplayName("A batida de ontem que ficou aberta não deixa ninguém online hoje")
    void ontemNaoConta() {
        bater(BRUNO, SEXTA.minusDays(1), "08:00");

        assertThat(de(painel(ADMIN, SEXTA, "10:00"), BRUNO).situacao()).isEqualTo(Situacao.SEM_BATIDA);
    }
}
