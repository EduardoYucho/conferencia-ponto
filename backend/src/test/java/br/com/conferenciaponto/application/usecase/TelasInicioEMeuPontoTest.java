package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.BancoView;
import br.com.conferenciaponto.application.tela.ConsultarBancoUseCase;
import br.com.conferenciaponto.application.tela.ConsultarInicioUseCase;
import br.com.conferenciaponto.application.tela.ConsultarMeuPontoUseCase;
import br.com.conferenciaponto.application.tela.DiaView;
import br.com.conferenciaponto.application.tela.DiaView.Filtro;
import br.com.conferenciaponto.application.tela.DiaView.Situacao;
import br.com.conferenciaponto.application.tela.DiaView.Tom;
import br.com.conferenciaponto.application.tela.Horas;
import br.com.conferenciaponto.application.tela.InicioView;
import br.com.conferenciaponto.application.tela.InicioView.Pendencia;
import br.com.conferenciaponto.application.tela.MeuPontoView;
import br.com.conferenciaponto.application.tela.MontadorDeDias;
import br.com.conferenciaponto.application.tela.MontadorDeDias.Quem;
import br.com.conferenciaponto.application.tela.NomesDeBatida;
import br.com.conferenciaponto.application.view.PresencaView;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * As telas "Início" e "Meu ponto" recebem tudo decidido pelo servidor: situação, frase, cor, próxima batida,
 * quanto falta, o que dá para fazer com cada dia. Estes testes fixam essas decisões.
 * (Fica neste pacote para usar os repositórios em memória dos outros testes.)
 */
class TelasInicioEMeuPontoTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    /** Sexta-feira. Horário padrão: 08:00–12:00 e 13:00–17:48 (8h 48min), tolerância de 5 minutos. */
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 2);
    private static final LocalDate ONTEM = HOJE.minusDays(1);
    private static final Quem DONO = new Quem(true, false);
    private static final Quem DONO_ADMIN = new Quem(true, true);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final AusenciaRepositoryEmMemoria ausencias = new AusenciaRepositoryEmMemoria();
    private final CalendarioFeriadosEmMemoria feriados = new CalendarioFeriadosEmMemoria();
    private final LancamentoBancoRepositoryEmMemoria lancamentos = new LancamentoBancoRepositoryEmMemoria();
    private final DivergenciaRepositoryEmMemoria divergencias = new DivergenciaRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final AjusteJornadaRepositoryEmMemoria ajustes = new AjusteJornadaRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(feriados, ausencias));

    // ------------------------------------------------------------------ montagem

    private Clock relogio(String hora) {
        return Clock.fixed(LocalDateTime.of(HOJE, LocalTime.parse(hora)).atZone(FUSO).toInstant(), FUSO);
    }

    private MontadorDeDias montador(Clock clock) {
        return new MontadorDeDias(registros, regras, ausencias, feriados, lancamentos, divergencias, arquivos,
                new ArmazenamentoEmMemoria(), ajustes, clock);
    }

    private ConsultarJornadaUseCase consultar() {
        return new ConsultarJornadaUseCase(registros, regras, ausencias, feriados, lancamentos,
                new ConsolidacaoBancoHoras(registros, lancamentos));
    }

    private InicioView inicio(String hora, Quem quem, String outraPessoa) {
        Clock clock = relogio(hora);
        var ciclo = new GerenciarCicloBancoUseCase(ciclos, new ConsolidacaoBancoHoras(registros, lancamentos),
                new ParametrosBancoHoras(LocalDate.of(2026, 5, 25), 6), e -> { }, clock);
        return new ConsultarInicioUseCase(montador(clock), consultar(), ciclo, divergencias, regras, clock)
                .agora(USUARIO, quem, outraPessoa);
    }

    private InicioView inicio(String hora) {
        return inicio(hora, DONO, null);
    }

    private DiaView dia(LocalDate data, Quem quem) {
        return montador(relogio("10:00")).montar(USUARIO, data, data, quem).get(0);
    }

    private DiaView dia(LocalDate data) {
        return dia(data, DONO);
    }

    private RegistroJornada bater(LocalDate data, String... horarios) {
        RegistroJornada registro = RegistroJornada.novo(USUARIO, data, regras.classificar(USUARIO, data));
        for (String horario : horarios) {
            registro.registrarBatida(LocalTime.parse(horario), regras.motor(USUARIO, data));
        }
        return registros.salvar(registro);
    }

    // ------------------------------------------------------------------ textos

    @Test
    @DisplayName("Horas e datas são escritas como se fala: '8h 48min', '+ 15 min a favor', 'Qua, 30/09'")
    void textos() {
        assertThat(Horas.duracao(31_680)).isEqualTo("8h 48min");
        assertThat(Horas.duracao(14_760)).isEqualTo("4h 06min");
        assertThat(Horas.duracao(3_600)).isEqualTo("1h");
        assertThat(Horas.duracao(720)).isEqualTo("12 min");
        assertThat(Horas.duracao(45)).isEqualTo("45 s");
        assertThat(Horas.duracao(0)).isEqualTo("0 min");
        assertThat(Horas.duracao(-720)).isEqualTo("12 min");
        assertThat(Horas.saldo(900)).isEqualTo("+ 15 min");
        assertThat(Horas.saldo(-720)).isEqualTo("− 12 min");
        assertThat(Horas.saldoComSentido(900)).isEqualTo("+ 15 min a favor");
        assertThat(Horas.saldoComSentido(-720)).isEqualTo("− 12 min devendo");
        assertThat(Horas.saldoComSentido(0)).isEqualTo("em dia");
        assertThat(Horas.dia(LocalDate.of(2026, 9, 30))).isEqualTo("Qua, 30/09");
        assertThat(Horas.diaLongo(LocalDate.of(2026, 9, 30))).isEqualTo("Quarta, 30/09");
        assertThat(Horas.porExtenso(HOJE)).isEqualTo("sexta-feira, 2 de outubro de 2026");
        assertThat(Horas.mesAno(YearMonth.of(2026, 9))).isEqualTo("Setembro de 2026");
        assertThat(Horas.saudacao(LocalTime.of(8, 0))).isEqualTo("Bom dia");
        assertThat(Horas.saudacao(LocalTime.of(13, 42))).isEqualTo("Boa tarde");
        assertThat(Horas.saudacao(LocalTime.of(19, 0))).isEqualTo("Boa noite");
    }

    @Test
    @DisplayName("Batidas têm os nomes usuais: Entrada, Saída p/ almoço, Volta do almoço, Saída")
    void nomesDasBatidas() {
        GradeHoraria padrao = GradeHoraria.PADRAO;
        assertThat(List.of(0, 1, 2, 3, 4, 5)).extracting(p -> NomesDeBatida.de(padrao, p))
                .containsExactly("Entrada", "Saída p/ almoço", "Volta do almoço", "Saída", "Volta", "Saída");
        assertThat(List.of(0, 1, 2, 3)).extracting(p -> NomesDeBatida.de(null, p))
                .containsExactly("Entrada", "Saída", "Volta", "Saída"); // dia sem expediente
        GradeHoraria comLanche = GradeHoraria.ler("13:00-15:30 16:00-19:00");
        assertThat(NomesDeBatida.de(comLanche, 1)).isEqualTo("Saída p/ intervalo");
        assertThat(NomesDeBatida.de(comLanche, 2)).isEqualTo("Volta do intervalo");
        assertThat(List.of(0, 1, 2, 3)).extracting(p -> NomesDeBatida.botao(padrao, p)).containsExactly(
                "Bater a entrada agora", "Bater a saída para o almoço", "Bater a volta do almoço", "Bater a saída agora");
        assertThat(NomesDeBatida.de(GradeHoraria.ler("07:00-13:00"), 1)).isEqualTo("Saída");
    }

    // ------------------------------------------------------------------ cada dia

    @Test
    @DisplayName("Dia fechado: em dia, a favor ou devendo, com a frase e a batida que causou a diferença")
    void diaFechado() {
        bater(HOJE.minusDays(3), "08:02", "12:01", "13:00", "17:48");
        bater(HOJE.minusDays(2), "08:12", "12:00", "13:00", "17:48");
        bater(ONTEM, "08:00", "12:00", "13:00", "18:20");

        DiaView emDia = dia(HOJE.minusDays(3));
        assertThat(emDia.situacao()).isEqualTo(Situacao.EM_DIA);
        assertThat(emDia.situacaoTexto()).isEqualTo("em dia");
        assertThat(emDia.tom()).isEqualTo(Tom.NEUTRO);
        assertThat(emDia.descricao()).isNull();
        assertThat(emDia.batidas()).extracting(DiaView.Batida::rotulo)
                .containsExactly("Entrada", "Saída p/ almoço", "Volta do almoço", "Saída");
        assertThat(emDia.batidas()).extracting(DiaView.Batida::nota).containsOnly("no horário");
        assertThat(emDia.filtros()).isEmpty();
        assertThat(emDia.trabalhadoSegundos()).isEqualTo(8 * 3600 + 47 * 60);

        DiaView devendo = dia(HOJE.minusDays(2));
        assertThat(devendo.situacao()).isEqualTo(Situacao.DEVENDO);
        assertThat(devendo.situacaoTexto()).isEqualTo("− 12 min devendo");
        assertThat(devendo.tom()).isEqualTo(Tom.NEGATIVO);
        assertThat(devendo.descricao()).isEqualTo("entrada 12 min depois do horário");
        assertThat(devendo.batidas().get(0).tom()).isEqualTo(Tom.NEGATIVO);
        assertThat(devendo.batidas().get(0).tolerada()).isFalse();
        assertThat(devendo.filtros()).containsExactly(Filtro.DIFERENCA);
        assertThat(devendo.saldoSegundos()).isEqualTo(-720);

        DiaView aFavor = dia(ONTEM);
        assertThat(aFavor.situacao()).isEqualTo(Situacao.A_FAVOR);
        assertThat(aFavor.situacaoTexto()).isEqualTo("+ 32 min a favor");
        assertThat(aFavor.descricao()).isEqualTo("saída 32 min depois do horário");
        assertThat(aFavor.batidas().get(3).tom()).isEqualTo(Tom.POSITIVO);
        assertThat(aFavor.relativo()).isEqualTo("Ontem");
        assertThat(aFavor.rotulo()).isEqualTo("Qui, 01/10");
    }

    @Test
    @DisplayName("Entrada sem saída: hoje está 'em andamento'; num dia que já passou, 'faltou bater a saída'")
    void incompletoOuEmAndamento() {
        bater(ONTEM, "08:05", "12:00", "13:02");
        bater(HOJE, "08:02", "12:01", "13:00");

        DiaView incompleto = dia(ONTEM);
        assertThat(incompleto.situacao()).isEqualTo(Situacao.INCOMPLETO);
        assertThat(incompleto.situacaoTexto()).isEqualTo("Faltou bater a saída");
        assertThat(incompleto.faltando()).isEqualTo("sem saída");
        assertThat(incompleto.saldoSegundos()).isNull();
        assertThat(incompleto.acoes().corrigir()).isTrue();
        assertThat(incompleto.acoes().ajustar()).isTrue();
        assertThat(incompleto.filtros()).contains(Filtro.CORRIGIR);

        DiaView andamento = dia(HOJE);
        assertThat(andamento.situacao()).isEqualTo(Situacao.EM_ANDAMENTO);
        assertThat(andamento.situacaoTexto()).isEqualTo("Em andamento");
        assertThat(andamento.acoes().corrigir()).isFalse();
        assertThat(andamento.filtros()).doesNotContain(Filtro.CORRIGIR);
        assertThat(andamento.hoje()).isTrue();
        assertThat(andamento.relativo()).isEqualTo("Hoje");
    }

    @Test
    @DisplayName("Dias sem registro: de trabalho (para corrigir), sem expediente, feriado, folga e futuro — cada um com as suas ações")
    void diasSemRegistro() {
        LocalDate sabado = LocalDate.of(2026, 9, 26);
        LocalDate feriado = LocalDate.of(2026, 9, 7);
        LocalDate folga = LocalDate.of(2026, 9, 25);
        feriados.salvar(new Feriado(feriado, "Independência do Brasil"));
        ausencias.salvar(Ausencia.nova(USUARIO, folga, folga, TipoAusencia.FOLGA, "Folga de aniversário", "eduardo", Instant.EPOCH));

        DiaView semBatida = dia(LocalDate.of(2026, 9, 22));
        assertThat(semBatida.situacao()).isEqualTo(Situacao.SEM_REGISTRO);
        assertThat(semBatida.tom()).isEqualTo(Tom.ATENCAO);
        assertThat(semBatida.descricao()).startsWith("Dia de trabalho sem nenhuma batida");
        assertThat(semBatida.saldoSegundos()).as("dia sem registro não entra no saldo").isNull();
        assertThat(semBatida.previstoSegundos()).isEqualTo(31_680);
        assertThat(semBatida.acoes().informarBatidas()).isTrue();
        assertThat(semBatida.acoes().marcar()).isTrue();
        assertThat(semBatida.acoes().lancarHoras()).isFalse();
        assertThat(semBatida.filtros()).containsExactly(Filtro.CORRIGIR);

        DiaView fimDeSemana = dia(sabado);
        assertThat(fimDeSemana.situacao()).isEqualTo(Situacao.SEM_EXPEDIENTE);
        assertThat(fimDeSemana.descricao()).isEqualTo("Sábado, sem expediente");
        assertThat(fimDeSemana.acoes().lancarHoras()).isTrue();
        assertThat(fimDeSemana.acoes().ajustar()).isFalse();
        assertThat(fimDeSemana.acoes().informarBatidas()).isFalse();
        assertThat(fimDeSemana.filtros()).isEmpty();

        DiaView noFeriado = dia(feriado);
        assertThat(noFeriado.situacao()).isEqualTo(Situacao.FERIADO);
        assertThat(noFeriado.descricao()).isEqualTo("Independência do Brasil");
        assertThat(noFeriado.marcador().feriado()).isTrue();
        assertThat(noFeriado.acoes().lancarHoras()).isTrue();
        assertThat(noFeriado.acoes().removerMarcacao()).as("só o administrador remove feriado").isFalse();
        assertThat(dia(feriado, DONO_ADMIN).acoes().removerMarcacao()).isTrue();
        assertThat(noFeriado.filtros()).containsExactly(Filtro.FOLGA);

        DiaView deFolga = dia(folga);
        assertThat(deFolga.situacao()).isEqualTo(Situacao.AUSENCIA);
        assertThat(deFolga.situacaoTexto()).isEqualTo("Folga");
        assertThat(deFolga.descricao()).isEqualTo("Folga de aniversário · não conta como falta");
        assertThat(deFolga.marcador().ausenciaId()).isNotNull();
        assertThat(deFolga.acoes().removerMarcacao()).isTrue();
        assertThat(deFolga.acoes().marcar()).isFalse();
        assertThat(deFolga.acoes().lancarHoras()).isFalse();

        DiaView futuro = dia(HOJE.plusDays(3));
        assertThat(futuro.situacao()).isEqualTo(Situacao.FUTURO);
        assertThat(futuro.acoes().marcar()).isTrue();
        assertThat(futuro.acoes().ajustar()).isFalse();
        assertThat(futuro.acoes().informarBatidas()).isFalse();
        assertThat(futuro.filtros()).isEmpty();

        assertThat(dia(HOJE).situacaoTexto()).as("hoje sem batidas não é pendência").isEqualTo("Sem batidas ainda");
        assertThat(dia(HOJE).filtros()).isEmpty();
    }

    @Test
    @DisplayName("Trabalho em dia sem expediente conta inteiro a favor; lançamento feito à mão é editado, não ajustado")
    void trabalhoEmDiaSemExpediente() {
        LocalDate sabado = LocalDate.of(2026, 9, 26);
        RegistroJornada manual = RegistroJornada.novo(USUARIO, sabado, TipoDia.FIM_DE_SEMANA);
        manual.lancarManualmente(Batidas.deIntervalos(List.of(new Intervalo(LocalTime.of(9, 0), LocalTime.of(12, 30)))),
                regras.motor(USUARIO, sabado));
        registros.salvar(manual);

        DiaView dia = dia(sabado);
        assertThat(dia.situacao()).isEqualTo(Situacao.A_FAVOR);
        assertThat(dia.situacaoTexto()).isEqualTo("+ 3h 30min a favor");
        assertThat(dia.descricao()).isEqualTo("trabalho em dia sem expediente: conta inteiro a favor");
        assertThat(dia.batidas()).extracting(DiaView.Batida::rotulo).containsExactly("Entrada", "Saída");
        assertThat(dia.batidas()).extracting(DiaView.Batida::nota).containsOnlyNulls();
        assertThat(dia.lancadoAMao()).isTrue();
        assertThat(dia.acoes().editarLancamento()).isTrue();
        assertThat(dia.acoes().ajustar()).isFalse();
        assertThat(dia.filtros()).contains(Filtro.AJUSTADO, Filtro.DIFERENCA);
    }

    @Test
    @DisplayName("Comprovante, diferença com o RH e lançamento no banco aparecem no dia; com comprovante o dia não pode ser excluído")
    void comprovanteDivergenciaELancamento() {
        RegistroJornada registro = bater(ONTEM, "08:00", "12:00", "13:00", "17:48");
        UUID comprovante = UUID.randomUUID();
        arquivos.salvar(new ComprovanteArquivado(comprovante, registro.getId(), "2026/10/c.pdf", TipoBatida.ENTRADA_1,
                Instant.EPOCH, LocalDateTime.of(ONTEM, LocalTime.of(8, 0)), "c.pdf", "a".repeat(64), 10));
        divergencias.salvar(Divergencia.nova(USUARIO, ONTEM, UUID.randomUUID(), new Divergencia.Achado(
                TipoDivergencia.HORARIO_DIFERENTE, "Saída diferente", true, null, List.of(), List.of(), null, TipoDia.UTIL,
                60, 0), Instant.EPOCH));
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, ONTEM, -14_400, "Saída antecipada", "eduardo", Instant.EPOCH));

        DiaView dia = dia(ONTEM);
        assertThat(dia.batidas().get(0).comprovanteId()).isEqualTo(comprovante);
        assertThat(dia.batidas().get(0).urlComprovante()).isNotBlank();
        assertThat(dia.batidas().get(1).comprovanteId()).isNull();
        assertThat(dia.comprovantes()).hasSize(1);
        assertThat(dia.acoes().excluir()).isFalse();
        assertThat(dia.divergenciaRh()).isTrue();
        assertThat(dia.acoes().conferirRh()).isTrue();
        assertThat(dia.lancadoSegundos()).isEqualTo(-14_400);
        assertThat(dia.lancamentos()).hasSize(1);

        bater(HOJE.minusDays(2), "08:00", "12:00", "13:00", "17:48");
        assertThat(dia(HOJE.minusDays(2)).acoes().excluir()).as("sem comprovante, dá para excluir").isTrue();
    }

    @Test
    @DisplayName("Quem só consulta (coordenação, ou administrador vendo outra pessoa) não recebe nenhuma ação")
    void soConsulta() {
        bater(ONTEM, "08:05", "12:00", "13:02");
        DiaView dia = dia(ONTEM, Quem.SO_CONSULTA);
        assertThat(dia.situacao()).isEqualTo(Situacao.INCOMPLETO);
        assertThat(dia.acoes()).isEqualTo(DiaView.Acoes.NENHUMA);
    }

    // ------------------------------------------------------------------ meu ponto

    @Test
    @DisplayName("Meu ponto: dias até hoje do mais recente para o mais antigo, os próximos à parte, totais e contagem dos filtros")
    void meuPonto() {
        bater(ONTEM, "08:12", "12:00", "13:00", "17:48");
        bater(HOJE, "08:02");
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, ONTEM, -3_600, "Saída antecipada", "eduardo", Instant.EPOCH));
        Clock clock = relogio("10:00");

        MeuPontoView outubro = new ConsultarMeuPontoUseCase(montador(clock), consultar(), regras, clock)
                .mes(USUARIO, YearMonth.of(2026, 10), DONO);

        assertThat(outubro.titulo()).isEqualTo("Outubro de 2026");
        assertThat(outubro.mesAtual()).isTrue();
        assertThat(outubro.dias()).extracting(DiaView::data).containsExactly(HOJE, ONTEM);
        assertThat(outubro.proximos()).hasSize(29).first().extracting(DiaView::data).isEqualTo(HOJE.plusDays(1));
        assertThat(outubro.totais().diasFechados()).isEqualTo(1);
        assertThat(outubro.totais().saldoDosDiasSegundos()).isEqualTo(-720);
        assertThat(outubro.totais().lancadoSegundos()).isEqualTo(-3_600);
        assertThat(outubro.totais().saldoSegundos()).isEqualTo(-4_320);
        assertThat(outubro.totais().trabalhadoSegundos()).isEqualTo(8 * 3600 + 36 * 60);
        assertThat(outubro.totais().previstoSegundos()).isEqualTo(31_680);
        assertThat(outubro.totais().diasParaCorrigir()).isZero();
        assertThat(outubro.contagem()).containsEntry(Filtro.DIFERENCA, 1).containsEntry(Filtro.CORRIGIR, 0);
        assertThat(outubro.explicacao()).contains("08:00–12:00 e 13:00–17:48").contains("8h 48min por dia")
                .contains("de segunda a sexta").contains("até 5 minutos");

        MeuPontoView setembro = new ConsultarMeuPontoUseCase(montador(clock), consultar(), regras, clock)
                .mes(USUARIO, YearMonth.of(2026, 9), DONO);
        assertThat(setembro.mesAtual()).isFalse();
        assertThat(setembro.dias()).hasSize(30).first().extracting(DiaView::data).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(setembro.proximos()).isEmpty();
        assertThat(setembro.totais().diasSemRegistro()).isEqualTo(22); // dias de trabalho de setembro, todos sem batida
        assertThat(setembro.contagem()).containsEntry(Filtro.CORRIGIR, 22);
    }

    // ------------------------------------------------------------------ início

    @Test
    @DisplayName("Início: trabalhando no último período, diz quanto falta e a hora de saída que fecha o dia zerado")
    void inicioTrabalhando() {
        bater(HOJE, "08:02", "12:01", "13:00");

        InicioView tela = inicio("13:42");
        InicioView.Hoje hoje = tela.dia();

        assertThat(tela.saudacao()).isEqualTo("Boa tarde");
        assertThat(tela.dataPorExtenso()).isEqualTo("sexta-feira, 2 de outubro de 2026");
        assertThat(hoje.situacao()).isEqualTo(PresencaView.Situacao.TRABALHANDO);
        assertThat(hoje.titulo()).isEqualTo("Você está trabalhando");
        assertThat(hoje.detalhe()).isEqualTo("desde 13:00");
        assertThat(hoje.trabalhando()).isTrue();
        assertThat(hoje.trabalhadoSegundos()).isEqualTo(4 * 3600 + 41 * 60);
        assertThat(hoje.previstoSegundos()).isEqualTo(31_680);
        assertThat(hoje.percentual()).isEqualTo(53);
        assertThat(hoje.resumo()).isEqualTo("Faltam 4h 06min. Saindo às 17:48 o dia fecha sem dever nem sobrar.");
        assertThat(hoje.proximaBatida()).isEqualTo("Saída");
        assertThat(hoje.proximaPrevista()).isEqualTo(LocalTime.of(17, 48));
        assertThat(hoje.podeBater()).isTrue();
        assertThat(hoje.rotuloBotao()).isEqualTo("Bater a saída agora");
        assertThat(hoje.aviso()).isNull();
        assertThat(hoje.dia().batidas()).hasSize(3);
    }

    @Test
    @DisplayName("Início: quem chegou atrasado vê a hora de saída que zera o dia; de manhã, só quanto falta")
    void inicioAtrasadoEManha() {
        bater(HOJE, "08:12", "12:00", "13:00");
        assertThat(inicio("13:42").dia().resumo())
                .isEqualTo("Faltam 4h 18min. Para não ficar devendo, a saída é às 18:00.");

        registros.excluir(registros.buscarPorData(USUARIO, HOJE).orElseThrow());
        bater(HOJE, "08:02");
        InicioView.Hoje manha = inicio("10:00").dia();
        assertThat(manha.resumo()).isEqualTo("Faltam 6h 48min para completar o dia.");
        assertThat(manha.proximaBatida()).isEqualTo("Saída p/ almoço");
        assertThat(manha.proximaPrevista()).isEqualTo(LocalTime.of(12, 0));
        assertThat(manha.rotuloBotao()).isEqualTo("Bater a saída para o almoço");
    }

    @Test
    @DisplayName("Início: intervalo, dia encerrado (botão bloqueado com explicação) e saída dentro da tolerância")
    void inicioIntervaloEEncerrado() {
        bater(HOJE, "08:02", "12:01");
        InicioView.Hoje intervalo = inicio("12:30").dia();
        assertThat(intervalo.situacao()).isEqualTo(PresencaView.Situacao.INTERVALO);
        assertThat(intervalo.titulo()).isEqualTo("Você está em intervalo");
        assertThat(intervalo.detalhe()).isEqualTo("desde 12:01");
        assertThat(intervalo.resumo()).isEqualTo("Faltam 4h 48min para completar o dia.");
        assertThat(intervalo.rotuloBotao()).isEqualTo("Bater a volta do almoço");
        assertThat(intervalo.trabalhando()).isFalse();

        bater(HOJE, "08:02", "12:01", "13:00");
        assertThat(inicio("17:45").dia().resumo()).as("3 minutos antes do fim: a tolerância cobre")
                .isEqualTo("A jornada de hoje está completa: já dá para bater a saída.");
        assertThat(inicio("18:30").dia().resumo()).isEqualTo("A jornada de hoje já passou em 42 min.");

        bater(HOJE, "08:02", "12:01", "13:00", "17:50");
        InicioView.Hoje encerrado = inicio("18:00").dia();
        assertThat(encerrado.situacao()).isEqualTo(PresencaView.Situacao.ENCERROU);
        assertThat(encerrado.titulo()).isEqualTo("Dia encerrado");
        assertThat(encerrado.detalhe()).isEqualTo("saída às 17:50");
        assertThat(encerrado.resumo()).isEqualTo("O dia fechou em dia.");
        assertThat(encerrado.percentual()).isEqualTo(100);
        assertThat(encerrado.podeBater()).isFalse();
        assertThat(encerrado.rotuloBotao()).isEqualTo("Jornada completa");
        assertThat(encerrado.aviso()).contains("Esqueci de bater");
        assertThat(encerrado.proximaBatida()).isNull();
    }

    @Test
    @DisplayName("Início sem batida: antes do horário, durante e depois; feriado, folga e fim de semana têm título próprio")
    void inicioSemBatida() {
        InicioView.Hoje cedo = inicio("07:10").dia();
        assertThat(cedo.situacao()).isEqualTo(PresencaView.Situacao.ANTES_DO_EXPEDIENTE);
        assertThat(cedo.titulo()).isEqualTo("O expediente começa às 08:00");
        assertThat(cedo.resumo()).isEqualTo("A jornada de hoje é de 8h 48min (08:00–12:00 e 13:00–17:48).");
        assertThat(cedo.rotuloBotao()).isEqualTo("Bater a entrada agora");
        assertThat(cedo.podeBater()).isTrue();
        assertThat(cedo.percentual()).isZero();
        assertThat(cedo.trabalhadoSegundos()).isZero();

        assertThat(inicio("10:00").dia().titulo()).isEqualTo("Você ainda não bateu o ponto hoje");
        assertThat(inicio("10:00").dia().detalhe()).isEqualTo("o expediente começou às 08:00");
        assertThat(inicio("19:00").dia().situacao()).isEqualTo(PresencaView.Situacao.NAO_REGISTROU);
        assertThat(inicio("19:00").dia().resumo()).contains("Esqueci de bater");

        ausencias.salvar(Ausencia.nova(USUARIO, HOJE.minusDays(2), HOJE.plusDays(13), TipoAusencia.FERIAS, null, "eduardo", Instant.EPOCH));
        InicioView.Hoje ferias = inicio("10:00").dia();
        assertThat(ferias.situacao()).isEqualTo(PresencaView.Situacao.AUSENCIA);
        assertThat(ferias.titulo()).isEqualTo("Hoje você está de férias");
        assertThat(ferias.detalhe()).isEqualTo("até 15/10");
        assertThat(ferias.previstoSegundos()).isZero();
    }

    @Test
    @DisplayName("Início de outra pessoa (administrador ou coordenação): frases na terceira pessoa e sem botão de bater")
    void inicioDeOutraPessoa() {
        bater(HOJE, "08:02");
        InicioView.Hoje hoje = inicio("10:00", Quem.SO_CONSULTA, "Maria").dia();
        assertThat(hoje.titulo()).isEqualTo("Maria está trabalhando");
        assertThat(hoje.podeBater()).isFalse();
        assertThat(hoje.aviso()).isNull();
        assertThat(hoje.dia().acoes()).isEqualTo(DiaView.Acoes.NENHUMA);
        assertThat(inicio("10:00", Quem.SO_CONSULTA, "Maria").mes().texto()).doesNotContain("seu");
    }

    @Test
    @DisplayName("Início: pendências (dia incompleto, dias sem registro, diferenças com o RH), saldos e últimos dias")
    void inicioPendenciasESaldos() {
        bater(LocalDate.of(2026, 9, 30), "08:05", "12:00", "13:02");          // faltou a saída
        bater(ONTEM, "08:00", "12:00", "13:00", "18:03");                       // + 15 min
        bater(HOJE, "08:02", "12:01", "13:00");
        // todos os outros dias de trabalho da janela marcados como férias, menos dois sem registro
        ausencias.salvar(Ausencia.nova(USUARIO, HOJE.minusDays(45), LocalDate.of(2026, 9, 27), TipoAusencia.FERIAS, null,
                "eduardo", Instant.EPOCH));
        for (int i = 0; i < 2; i++) {
            divergencias.salvar(Divergencia.nova(USUARIO, HOJE.minusDays(20 + i), UUID.randomUUID(), new Divergencia.Achado(
                    TipoDivergencia.HORARIO_DIFERENTE, "x", true, null, List.of(), List.of(), null, TipoDia.UTIL, 60, 0),
                    Instant.EPOCH));
        }

        InicioView tela = inicio("13:42");

        assertThat(tela.pendencias()).extracting(Pendencia::tipo).containsExactly(Pendencia.Tipo.DIA_INCOMPLETO,
                Pendencia.Tipo.DIA_SEM_REGISTRO, Pendencia.Tipo.DIVERGENCIAS_RH);
        Pendencia incompleto = tela.pendencias().get(0);
        assertThat(incompleto.texto()).isEqualTo("Quarta, 30/09: faltou bater a saída");
        assertThat(incompleto.acao()).isEqualTo("Corrigir");
        assertThat(incompleto.data()).isEqualTo(LocalDate.of(2026, 9, 30));
        Pendencia semRegistro = tela.pendencias().get(1);
        assertThat(semRegistro.texto()).isEqualTo("2 dias de trabalho sem batidas"); // 28 e 29/09
        assertThat(semRegistro.acao()).isEqualTo("Ver os dias");
        assertThat(semRegistro.data()).isNull();
        assertThat(tela.pendencias().get(2).texto()).isEqualTo("RH: 2 dias diferentes do ponto");

        assertThat(tela.mes().nome()).isEqualTo("outubro");
        assertThat(tela.mes().saldoSegundos()).isEqualTo(900);
        assertThat(tela.mes().texto()).isEqualTo("a seu favor, em 1 dia fechado");
        assertThat(tela.banco().saldoSegundos()).isEqualTo(900);
        assertThat(tela.banco().texto()).isEqualTo("a seu favor desde 25/05");
        assertThat(tela.banco().prazo()).isEqualTo("O RH fecha em 24/11 (faltam 53 dias)");
        assertThat(tela.banco().urgente()).isFalse();

        assertThat(tela.ultimosDias()).extracting(DiaView::data).containsExactly(HOJE, ONTEM, LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 28));
        assertThat(tela.ultimosDias().get(0).relativo()).isEqualTo("Hoje");
        assertThat(tela.ultimosDias().get(1).situacaoTexto()).isEqualTo("+ 15 min a favor");
    }

    @Test
    @DisplayName("Banco de horas: saldo e prazo escritos, meses do ciclo, horas usadas à mão e fechamento anterior")
    void bancoDeHoras() {
        bater(LocalDate.of(2026, 9, 29), "08:00", "12:00", "13:00", "18:48");   // + 1h
        bater(ONTEM, "08:00", "12:00", "13:00", "18:03");                       // + 15 min
        Clock clock = relogio("10:00");
        var ciclo = new GerenciarCicloBancoUseCase(ciclos, new ConsolidacaoBancoHoras(registros, lancamentos),
                new ParametrosBancoHoras(LocalDate.of(2026, 5, 25), 6), e -> { }, clock);
        var lancar = new GerenciarLancamentosBancoUseCase(lancamentos, ciclos, e -> { }, clock);
        ciclo.garantirCicloAberto(USUARIO);
        lancar.lancar(USUARIO, LocalDate.of(2026, 9, 30), -1800, "Saí mais cedo", "eduardo");
        var useCase = new ConsultarBancoUseCase(ciclo, lancar, clock);

        BancoView tela = useCase.agora(USUARIO, true, null);

        assertThat(tela.resumo().saldoSegundos()).isEqualTo(3600 + 900 - 1800);
        assertThat(tela.resumo().texto()).isEqualTo("a seu favor desde 25/05");
        assertThat(tela.resumo().prazo()).isEqualTo("O RH fecha em 24/11 (faltam 53 dias)");
        assertThat(tela.meses()).extracting(BancoView.Mes::rotulo).contains("Setembro", "Outubro");
        BancoView.Mes setembro = tela.meses().stream().filter(m -> m.mes() == 9).findFirst().orElseThrow();
        assertThat(setembro.saldoSegundos()).isEqualTo(1800);
        assertThat(setembro.texto()).isEqualTo("+ 30 min a favor");
        assertThat(setembro.atual()).isFalse();
        assertThat(tela.meses().stream().filter(BancoView.Mes::atual)).extracting(BancoView.Mes::mes).containsExactly(10);
        assertThat(tela.lancamentos()).hasSize(1);
        assertThat(tela.lancamentos().get(0).texto()).isEqualTo("Usou 30 min do banco");
        assertThat(tela.lancamentos().get(0).dia()).isEqualTo("Qua, 30/09");
        assertThat(tela.anteriores()).isEmpty();
        assertThat(tela.podeEditar()).isTrue();
        assertThat(tela.podeDesfazer()).isFalse();

        // fechado em 30/09: o ciclo novo começa em 01/10 só com o saldo de outubro; o anterior aparece na lista
        ciclo.fechar(USUARIO, LocalDate.of(2026, 9, 30), "Fechamento do RH", "eduardo");
        BancoView depois = useCase.agora(USUARIO, true, null);
        assertThat(depois.resumo().saldoSegundos()).isEqualTo(900);
        assertThat(depois.resumo().texto()).isEqualTo("a seu favor desde 01/10");
        assertThat(depois.anteriores()).hasSize(1);
        assertThat(depois.anteriores().get(0).periodo()).isEqualTo("25/05/2026 a 30/09/2026");
        assertThat(depois.anteriores().get(0).texto()).isEqualTo("fechou com + 30 min a favor");
        assertThat(depois.podeDesfazer()).isTrue();

        // outra pessoa olhando: nada de editar nem desfazer, e a frase não diz "seu"
        BancoView consulta = useCase.agora(USUARIO, false, "Maria");
        assertThat(consulta.podeEditar()).isFalse();
        assertThat(consulta.podeDesfazer()).isFalse();
        assertThat(consulta.resumo().texto()).isEqualTo("a favor desde 01/10");
    }
}
