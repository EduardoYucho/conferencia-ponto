package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.Estilo;
import br.com.conferenciaponto.application.planilha.MontadorPlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static br.com.conferenciaponto.application.usecase.Fixtures.OUTRO;
import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;

class MontadorPlanilhaConferenciaTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 10, 2);
    private static final int JUNHO = 202606;
    private static final int OUTUBRO = 202610;

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final LancamentoBancoRepositoryEmMemoria lancamentos = new LancamentoBancoRepositoryEmMemoria();
    private final AusenciaRepositoryEmMemoria ausencias = new AusenciaRepositoryEmMemoria();
    private final CalendarioFeriadosEmMemoria feriados = new CalendarioFeriadosEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(HOJE, LocalTime.of(14, 30), SP).toInstant(), SP);
    private final ConsolidacaoBancoHoras consolidacao = new ConsolidacaoBancoHoras(registros, lancamentos);
    private final GerenciarCicloBancoUseCase ciclo = new GerenciarCicloBancoUseCase(ciclos, consolidacao,
            new ParametrosBancoHoras(LocalDate.of(2026, 5, 25), 6), evento -> { }, clock);
    private MontadorPlanilhaConferencia montador;

    @BeforeEach
    void montar() {
        var regras = Fixtures.regras(new ClassificadorDiaService(feriados, ausencias));
        var consultar = new ConsultarJornadaUseCase(registros, regras, ausencias, feriados, lancamentos, consolidacao);
        var auditoria = new ConsultarAuditoriaUseCase(consultar, new ComprovanteArquivadoRepositoryEmMemoria(),
                new ArmazenamentoEmMemoria(), new AjusteJornadaRepositoryEmMemoria());
        montador = new MontadorPlanilhaConferencia(UsuarioRepositoryEmMemoria.comTitular(), consolidacao, auditoria,
                ciclo, regras, clock);
        ciclo.garantirCicloAberto(USUARIO);
    }

    /** Dia fechado: entrada às 08:00 e saída ajustada para dar o saldo. */
    private void dia(LocalDate data, int saldoSegundos) {
        RegistroJornada r = RegistroJornada.novo(USUARIO, data, TipoDia.UTIL);
        for (LocalTime t : List.of(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(13, 0),
                LocalTime.of(17, 48).plusSeconds(saldoSegundos))) {
            r.incluirBatida(t, motor);
        }
        registros.salvar(r);
    }

    private void emAberto(LocalDate data) {
        RegistroJornada r = RegistroJornada.novo(USUARIO, data, TipoDia.UTIL);
        r.incluirBatida(LocalTime.of(8, 0), motor);
        r.incluirBatida(LocalTime.of(12, 0), motor);
        r.incluirBatida(LocalTime.of(13, 0), motor);
        registros.salvar(r);
    }

    private static Celula celula(Aba aba, String ref) {
        int coluna = ref.charAt(0) - 'A';
        int linha = Integer.parseInt(ref.substring(1)) - 1;
        return aba.linhas().get(linha).get(coluna);
    }

    private static String texto(Aba aba, String ref) {
        return celula(aba, ref).texto();
    }

    @Test
    @DisplayName("Sem registros: só a aba de resumo, avisando que não há dados")
    void semRegistros() {
        PlanilhaConferencia planilha = montador.montar(USUARIO);

        assertThat(planilha.abas()).extracting(Aba::titulo).containsExactly("Resumo");
        assertThat(planilha.abas().get(0).linhas().stream().flatMap(List::stream).map(Celula::texto))
                .contains("Ainda não há registros de ponto.");
    }

    @Test
    @DisplayName("Uma aba por mês, do primeiro ao último com registro, sem pular os meses do meio; a mais recente primeiro")
    void umaAbaPorMes() {
        dia(LocalDate.of(2026, 6, 1), 0);
        dia(LocalDate.of(2026, 8, 3), 600);
        dia(LocalDate.of(2026, 6, 2), 0);
        registros.salvar(restaurarDe(OUTRO, LocalDate.of(2026, 3, 2)));

        PlanilhaConferencia planilha = montador.montar(USUARIO);

        assertThat(planilha.abas()).extracting(Aba::titulo).containsExactly("Resumo", "Ago 2026", "Jul 2026", "Jun 2026");
        assertThat(planilha.abas()).extracting(Aba::id).containsExactly(Aba.ID_RESUMO, 202608, 202607, JUNHO);
        assertThat(planilha.abas()).allSatisfy(aba -> assertThat(Aba.idDeMes(aba.id())).isEqualTo(aba.id() != Aba.ID_RESUMO));
    }

    private RegistroJornada restaurarDe(java.util.UUID usuario, LocalDate data) {
        RegistroJornada r = RegistroJornada.novo(usuario, data, TipoDia.UTIL);
        r.incluirBatida(LocalTime.of(8, 0), motor);
        r.incluirBatida(LocalTime.of(12, 0), motor);
        return r;
    }

    @Test
    @DisplayName("Aba do mês: todos os dias do calendário, totais em fórmula iguais aos do sistema e dia em aberto fora deles")
    void abaDoMes() {
        dia(LocalDate.of(2026, 6, 1), 600);
        dia(LocalDate.of(2026, 6, 2), -1_800);
        emAberto(LocalDate.of(2026, 6, 3));
        // 3 minutos de atraso, dentro da tolerância: o saldo fica zerado
        RegistroJornada tolerado = RegistroJornada.novo(USUARIO, LocalDate.of(2026, 6, 5), TipoDia.UTIL);
        for (LocalTime t : List.of(LocalTime.of(8, 3), LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(17, 48))) {
            tolerado.incluirBatida(t, motor);
        }
        registros.salvar(tolerado);
        feriados.salvar(new Feriado(LocalDate.of(2026, 6, 4), "Corpus Christi"));
        ausencias.salvar(Ausencia.nova(USUARIO, LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 9), TipoAusencia.FERIAS,
                "Férias de inverno", "eduardo", Instant.EPOCH));
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, LocalDate.of(2026, 6, 6), -14_400, "Folga compensada",
                "eduardo", Instant.EPOCH));

        Aba junho = montador.montar(USUARIO).aba(JUNHO).orElseThrow();

        assertThat(junho.linhasCongeladas()).isEqualTo(5);
        assertThat(texto(junho, "A1")).isEqualTo("Conferência de ponto · Junho de 2026");
        assertThat(texto(junho, "A2")).startsWith("Eduardo").contains("Atualizado em 02/10/2026 às 14:30");
        assertThat(junho.linhas().get(3)).extracting(Celula::texto).containsExactly("Data", "Dia", "Tipo de dia",
                "Entrada 1", "Saída 1", "Entrada 2", "Saída 2", "Trabalhado", "Previsto", "Tolerância",
                "Saldo do dia", "Lançamento no banco", "Situação", "PDFs", "Observações");

        // 30 dias: linhas 6 a 35
        assertThat(celula(junho, "A6").data()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(celula(junho, "A35").data()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(texto(junho, "B6")).isEqualTo("seg");

        // dia fechado: batidas como horário, horas como duração
        assertThat(celula(junho, "D6").tipo()).isEqualTo(Celula.Tipo.HORA);
        assertThat(celula(junho, "D6").valor()).isEqualTo(8 * 3600);
        assertThat(celula(junho, "G6").valor()).isEqualTo(LocalTime.of(17, 58).toSecondOfDay());
        assertThat(celula(junho, "H6").valor()).isEqualTo(31_680 + 600);
        assertThat(celula(junho, "I6").valor()).isEqualTo(31_680);
        assertThat(celula(junho, "K6").valor()).isEqualTo(600);
        assertThat(celula(junho, "K6").estilo().texto()).isEqualTo(Estilo.Cor.CREDITO);
        assertThat(celula(junho, "K7").valor()).isEqualTo(-1_800);
        assertThat(celula(junho, "K7").estilo().texto()).isEqualTo(Estilo.Cor.DEBITO);
        assertThat(texto(junho, "M6")).isEqualTo("Fechada");

        // dia em aberto (já passou): batidas aparecem, horas ficam em branco
        assertThat(celula(junho, "F8").valor()).isEqualTo(13 * 3600);
        assertThat(celula(junho, "H8").tipo()).isEqualTo(Celula.Tipo.VAZIA);
        assertThat(celula(junho, "K8").tipo()).isEqualTo(Celula.Tipo.VAZIA);
        assertThat(texto(junho, "M8")).isEqualTo("Incompleto");
        assertThat(texto(junho, "O8")).contains("Trabalhado até a última batida: 04:00:00");

        // feriado, lançamento num sábado, férias, dia útil sem registro
        assertThat(texto(junho, "C9")).isEqualTo("Feriado");
        assertThat(texto(junho, "O9")).isEqualTo("Corpus Christi");
        assertThat(texto(junho, "C11")).isEqualTo("Fim de semana");
        assertThat(celula(junho, "L11").valor()).isEqualTo(-14_400);
        assertThat(texto(junho, "O11")).isEqualTo("Banco de horas -04:00:00: Folga compensada (por eduardo)");
        assertThat(texto(junho, "C13")).isEqualTo("Férias");
        assertThat(texto(junho, "O13")).isEqualTo("Férias de inverno");
        assertThat(celula(junho, "M13").tipo()).isEqualTo(Celula.Tipo.VAZIA);
        assertThat(texto(junho, "C15")).isEqualTo("Útil");
        assertThat(texto(junho, "M15")).isEqualTo("Sem registro");

        // tolerância: saldo do dia = trabalhado + tolerância - previsto
        assertThat(celula(junho, "H10").valor()).isEqualTo(31_680 - 180);
        assertThat(celula(junho, "J10").valor()).isEqualTo(180);
        assertThat(celula(junho, "K10").valor()).isZero();
        assertThat(celula(junho, "J6").tipo()).as("sem ajuste, a célula fica em branco").isEqualTo(Celula.Tipo.VAZIA);
        assertThat(celula(junho, "J5").formula()).isEqualTo("SUM(J6:J35)");
        assertThat(celula(junho, "J5").valor()).isEqualTo(180);
        assertThat(celula(junho, "H5").valor() + celula(junho, "J5").valor() - celula(junho, "I5").valor())
                .isEqualTo(celula(junho, "K5").valor());

        // totais: fórmulas sobre os dias, com o mesmo valor da consolidação do sistema
        SaldoMensal sistema = consolidacao.periodo(USUARIO, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)).get(0);
        assertThat(texto(junho, "A5")).isEqualTo("Total do mês");
        assertThat(celula(junho, "H5").formula()).isEqualTo("SUM(H6:H35)");
        assertThat(celula(junho, "H5").valor()).isEqualTo(sistema.segundosTrabalhados());
        assertThat(celula(junho, "I5").valor()).isEqualTo(sistema.segundosPrevistos()).isEqualTo(3 * 31_680);
        assertThat(celula(junho, "K5").formula()).isEqualTo("SUM(K6:K35)");
        assertThat(celula(junho, "K5").valor()).isEqualTo(sistema.saldoJornadasSegundos()).isEqualTo(-1_200);
        assertThat(celula(junho, "L5").valor()).isEqualTo(sistema.segundosLancados()).isEqualTo(-14_400);

        // resumo do mês, depois dos dias
        assertThat(texto(junho, "A37")).isEqualTo("Resumo do mês");
        assertThat(texto(junho, "A40")).isEqualTo("Tolerância");
        assertThat(celula(junho, "D40").formula()).isEqualTo("J5");
        assertThat(texto(junho, "A43")).isEqualTo("Saldo do mês");
        assertThat(celula(junho, "D43").formula()).isEqualTo("K5+L5");
        assertThat(celula(junho, "D43").valor()).isEqualTo(sistema.saldoMensalSegundos()).isEqualTo(-15_600);
        assertThat(celula(junho, "D44").valor()).isEqualTo(4);
        assertThat(celula(junho, "D45").valor()).isEqualTo(1);
    }

    @Test
    @DisplayName("Dia de hoje e dias futuros sem registro ficam em branco; a 3ª entrada e saída só aparecem no mês que tem")
    void hojeFuturoETerceiroPeriodo() {
        RegistroJornada r = RegistroJornada.novo(USUARIO, LocalDate.of(2026, 10, 1), TipoDia.UTIL);
        for (LocalTime t : List.of(LocalTime.of(8, 0), LocalTime.of(11, 0), LocalTime.of(11, 15), LocalTime.of(12, 0),
                LocalTime.of(13, 0), LocalTime.of(17, 48))) {
            r.incluirBatida(t, motor);
        }
        registros.salvar(r);

        Aba outubro = montador.montar(USUARIO).aba(OUTUBRO).orElseThrow();

        assertThat(outubro.linhas().get(3)).extracting(Celula::texto)
                .containsSequence("Saída 2", "Entrada 3", "Saída 3", "Trabalhado");
        assertThat(outubro.colunas()).isEqualTo(17);
        assertThat(celula(outubro, "I6").valor()).isEqualTo(LocalTime.of(17, 48).toSecondOfDay());
        assertThat(celula(outubro, "J5").formula()).isEqualTo("SUM(J6:J36)");
        // 02/10 é hoje e 05/10 ainda não chegou: nada de "Sem registro"
        assertThat(texto(outubro, "O6")).isEqualTo("Fechada");
        assertThat(celula(outubro, "O7").tipo()).isEqualTo(Celula.Tipo.VAZIA);
        assertThat(celula(outubro, "O10").tipo()).isEqualTo(Celula.Tipo.VAZIA);
    }

    @Test
    @DisplayName("Resumo: uma linha por mês apontando para os totais da aba, total geral, banco de horas do ciclo e horário")
    void resumo() {
        dia(LocalDate.of(2026, 5, 22), 7_200);   // antes do ciclo aberto em 25/05
        dia(LocalDate.of(2026, 5, 26), 600);
        dia(LocalDate.of(2026, 6, 10), -1_000);
        lancamentos.salvar(LancamentoBanco.novo(USUARIO, LocalDate.of(2026, 6, 13), -3_600, "Saída antecipada",
                "eduardo", Instant.EPOCH));

        Aba resumo = montador.montar(USUARIO).aba(Aba.ID_RESUMO).orElseThrow();

        assertThat(texto(resumo, "A1")).isEqualTo("Conferência de ponto · Eduardo");
        // banco de horas: só o que está dentro do ciclo
        assertThat(texto(resumo, "A4")).isEqualTo("Banco de horas");
        assertThat(texto(resumo, "A6")).isEqualTo("Ciclo atual");
        assertThat(celula(resumo, "B6").data()).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(celula(resumo, "C6").data()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(celula(resumo, "G6").valor()).isEqualTo(600 - 1_000 - 3_600);

        // meses: total, depois junho e maio
        assertThat(texto(resumo, "A8")).isEqualTo("Meses");
        assertThat(texto(resumo, "A10")).isEqualTo("Total");
        assertThat(texto(resumo, "A11")).isEqualTo("Junho de 2026");
        assertThat(texto(resumo, "A12")).isEqualTo("Maio de 2026");
        assertThat(celula(resumo, "D11").formula()).isEqualTo("'Jun 2026'!H5");
        assertThat(celula(resumo, "F11").formula()).isEqualTo("'Jun 2026'!J5");
        assertThat(celula(resumo, "G11").formula()).isEqualTo("'Jun 2026'!K5");
        assertThat(celula(resumo, "G11").valor()).isEqualTo(-1_000);
        assertThat(celula(resumo, "H11").valor()).isEqualTo(-3_600);
        assertThat(celula(resumo, "I11").formula()).isEqualTo("'Jun 2026'!K5+'Jun 2026'!L5");
        assertThat(celula(resumo, "I11").valor()).isEqualTo(-4_600);
        assertThat(celula(resumo, "J11").valor()).as("banco no fim de junho").isEqualTo(600 - 4_600);
        assertThat(celula(resumo, "I12").valor()).as("maio inteiro").isEqualTo(7_800);
        assertThat(celula(resumo, "J12").valor()).as("banco no fim de maio: só desde 25/05").isEqualTo(600);
        assertThat(celula(resumo, "B10").formula()).isEqualTo("SUM(B11:B12)");
        assertThat(celula(resumo, "B10").valor()).isEqualTo(3);
        assertThat(celula(resumo, "I10").formula()).isEqualTo("SUM(I11:I12)");
        assertThat(celula(resumo, "I10").valor()).isEqualTo(3_200);

        // horário padrão: seg a sex, um período por linha dentro da célula
        assertThat(texto(resumo, "A14")).isEqualTo("Horário de trabalho");
        assertThat(texto(resumo, "A16")).isEqualTo("desde o início");
        assertThat(texto(resumo, "B16")).isEqualTo("08:00-12:00\n13:00-17:48");
        assertThat(texto(resumo, "G16")).isEqualTo("—");
        assertThat(texto(resumo, "I16")).isEqualTo("5 min");
    }

    @Test
    @DisplayName("Referências de célula: letras das colunas e aba entre aspas")
    void referencias() {
        assertThat(Aba.letra(0)).isEqualTo("A");
        assertThat(Aba.letra(25)).isEqualTo("Z");
        assertThat(Aba.letra(26)).isEqualTo("AA");
        assertThat(Aba.letra(27)).isEqualTo("AB");
        assertThat(Aba.ref(4, 7)).isEqualTo("H5");
        assertThat(Aba.idDeMes(202613)).isFalse();
        assertThat(Aba.idDeMes(0)).isFalse();
    }
}
