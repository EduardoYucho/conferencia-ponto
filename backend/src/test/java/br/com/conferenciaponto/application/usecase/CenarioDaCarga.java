package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.DiferencaDaCarga;
import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.LeiDaJornada;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;

/**
 * Uma pessoa com o horário padrão até setembro e, de 01/10/2026, 8h por dia (40h) com carga de 42h: faltam 2h por
 * semana, 24 min por dia útil. Hoje é quarta, 21/10/2026, às 18:00; 12/10 é feriado.
 */
class CenarioDaCarga {

    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    static final LocalDate OUTUBRO = LocalDate.of(2026, 10, 1);

    final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    final LancamentoBancoRepositoryEmMemoria lancamentos = new LancamentoBancoRepositoryEmMemoria();
    final AusenciaRepositoryEmMemoria ausencias = new AusenciaRepositoryEmMemoria();
    final CalendarioFeriadosEmMemoria feriados = new CalendarioFeriadosEmMemoria();
    final HorarioTrabalhoRepositoryEmMemoria horarios = new HorarioTrabalhoRepositoryEmMemoria();
    final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    final ConferenciaMesRepositoryEmMemoria conferencias = new ConferenciaMesRepositoryEmMemoria();
    final NotificacaoRepositoryEmMemoria notificacoesRepo = new NotificacaoRepositoryEmMemoria();
    final AjusteJornadaRepositoryEmMemoria ajustes = new AjusteJornadaRepositoryEmMemoria();
    final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    final List<Object> eventos = new ArrayList<>();
    final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(feriados, ausencias), horarios);

    Clock clock;
    DiferencaDaCarga carga;
    ConsolidacaoBancoHoras consolidacao;
    ConsultarJornadaUseCase jornada;
    ConsultarAuditoriaUseCase auditoria;
    GerenciarCicloBancoUseCase ciclo;
    NotificacoesUseCase notificacoes;
    ConferirMesUseCase conferir;
    TravaDosMesesConferidos trava;
    ConsultarBancoDeHorasUseCase banco;
    VerificarCompensacaoUseCase verificar;

    CenarioDaCarga() {
        feriados.cadastrar(LocalDate.of(2026, 10, 12), "Nossa Senhora Aparecida");
        horarios.salvar(regras.padraoParaGravar(USUARIO, "teste", Instant.EPOCH));
        horarios.salvar(new HorarioTrabalho(java.util.UUID.randomUUID(), USUARIO, OUTUBRO, 5, oitoHoras(),
                Instant.EPOCH, "teste", 42 * 3600));
        em(LocalDate.of(2026, 10, 21), LocalTime.of(18, 0));
    }

    /** Muda o "agora" (e refaz os casos de uso com o relógio novo). */
    CenarioDaCarga em(LocalDate dia, LocalTime hora) {
        clock = Clock.fixed(ZonedDateTime.of(dia, hora, SP).toInstant(), SP);
        regras.invalidar(USUARIO);
        carga = new DiferencaDaCarga(regras, registros);
        consolidacao = new ConsolidacaoBancoHoras(registros, lancamentos, carga);
        jornada = new ConsultarJornadaUseCase(registros, regras, ausencias, feriados, lancamentos, consolidacao);
        auditoria = new ConsultarAuditoriaUseCase(jornada, arquivos, new ArmazenamentoEmMemoria(), ajustes);
        ciclo = new GerenciarCicloBancoUseCase(ciclos, consolidacao, new ParametrosBancoHoras(LocalDate.of(2026, 5, 25), 6),
                eventos::add, clock);
        notificacoes = new NotificacoesUseCase(notificacoesRepo, eventos::add, clock);
        conferir = new ConferirMesUseCase(conferencias, jornada, notificacoes, eventos::add, clock);
        trava = new TravaDosMesesConferidos(conferencias, clock);
        banco = new ConsultarBancoDeHorasUseCase(auditoria, registros, regras, carga, LeiDaJornada.vigente(), conferir,
                ciclo, ajustes, clock);
        verificar = new VerificarCompensacaoUseCase(banco, carga, notificacoes);
        return this;
    }

    static Map<DayOfWeek, GradeHoraria> oitoHoras() {
        Map<DayOfWeek, GradeHoraria> dias = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek d : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY)) {
            dias.put(d, GradeHoraria.ler("08:00-12:00 13:00-17:00"));
        }
        return dias;
    }

    /** Um dia com as batidas (o tipo vem do calendário e do horário da data). */
    RegistroJornada dia(LocalDate data, String... batidas) {
        RegistroJornada r = RegistroJornada.novo(USUARIO, data, regras.classificar(USUARIO, data));
        for (String b : batidas) {
            r.incluirBatida(LocalTime.parse(b), regras.motor(USUARIO, data));
        }
        registros.salvar(r);
        return r;
    }

    /** Dia útil cheio do horário de outubro (8h: saldo zero). */
    RegistroJornada diaCheio(LocalDate data) {
        return dia(data, "08:00", "12:00", "13:00", "17:00");
    }
}
