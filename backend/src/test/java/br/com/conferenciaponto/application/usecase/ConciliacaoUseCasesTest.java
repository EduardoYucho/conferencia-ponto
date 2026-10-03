package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.DiaView.Tom;
import br.com.conferenciaponto.application.tela.MontadorDeDias.Quem;
import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase.Textos;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.application.view.DivergenciaView;
import br.com.conferenciaponto.application.view.DivergenciaView.Marca;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRhLido;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.ComparadorConciliacaoService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fluxo da conciliação com o RH: envio, conferência, aceite (individual e em lote), manter e reabrir. */
class ConciliacaoUseCasesTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final String USUARIO = "eduardo";
    private static final UUID DONO = Fixtures.USUARIO;

    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, SP).toInstant(), SP);
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final AjusteJornadaRepositoryEmMemoria ajustes = new AjusteJornadaRepositoryEmMemoria();
    private final AusenciaRepositoryEmMemoria ausenciasRepo = new AusenciaRepositoryEmMemoria();
    private final RelatorioRhRepositoryEmMemoria relatorios = new RelatorioRhRepositoryEmMemoria();
    private final DivergenciaRepositoryEmMemoria divergencias = new DivergenciaRepositoryEmMemoria();
    private final List<Object> eventos = new ArrayList<>();

    private final Set<LocalDate> datasFeriado = new HashSet<>();
    private final CalendarioFeriados feriados = new CalendarioFeriados() {
        @Override
        public boolean isFeriado(LocalDate data) {
            return datasFeriado.contains(data);
        }

        @Override
        public void cadastrar(LocalDate data, String descricao) {
            datasFeriado.add(data);
        }
    };
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(feriados, ausenciasRepo);
    private final RegrasJornada regras = Fixtures.regras(classificador);
    private final NotificacaoRepositoryEmMemoria notificacoesRepo = new NotificacaoRepositoryEmMemoria();
    private final NotificacoesUseCase notificacoes = new NotificacoesUseCase(notificacoesRepo, eventos::add, clock);
    private final ConferirConciliacaoUseCase conferir = new ConferirConciliacaoUseCase(relatorios, divergencias,
            registros, UsuarioRepositoryEmMemoria.comTitular(), regras, new ComparadorConciliacaoService(), notificacoes,
            eventos::add, clock);
    private RelatorioRhLido proximoLido;
    private final ImportarRelatorioRhUseCase importar =
            new ImportarRelatorioRhUseCase(pdf -> proximoLido, relatorios, conferir, eventos::add, clock);
    private final AjustarBatidasUseCase ajustar =
            new AjustarBatidasUseCase(registros, arquivos, ajustes, regras, eventos::add, clock);
    private final GerenciarAusenciasUseCase ausencias =
            new GerenciarAusenciasUseCase(ausenciasRepo, registros, regras, eventos::add, clock);
    private final PlatformTransactionManager semTransacao = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definicao) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };
    private final ResolverDivergenciaUseCase resolver = new ResolverDivergenciaUseCase(divergencias, relatorios,
            registros, ajustar, ausencias, feriados, regras, conferir, eventos::add, semTransacao, clock);
    private final ConsultarConciliacaoUseCase consultar =
            new ConsultarConciliacaoUseCase(relatorios, divergencias, registros, regras, ausenciasRepo);

    private static LocalDate d(int dia) {
        return LocalDate.of(2026, 7, dia);
    }

    private static List<LocalTime> h(String... horarios) {
        return Arrays.stream(horarios).map(LocalTime::parse).toList();
    }

    private static DiaRelatorioRh util(int dia, int saldo, String... horarios) {
        return new DiaRelatorioRh(d(dia), h(horarios), null, 31_680, 0, saldo);
    }

    private static DiaRelatorioRh ocorrencia(int dia, String texto) {
        return new DiaRelatorioRh(d(dia), List.of(), texto, 0, 0, 0);
    }

    private void local(int dia, String... horarios) {
        RegistroJornada r = RegistroJornada.novo(DONO, d(dia), regras.classificar(DONO, d(dia)));
        for (LocalTime t : h(horarios)) {
            r.incluirBatida(t, motor);
        }
        registros.salvar(r);
    }

    private RelatorioRh enviar(LocalDateTime emissao, List<DiaRelatorioRh> dias, byte[] conteudo) {
        proximoLido = new RelatorioRhLido("000042 - Teste", emissao, dias.get(0).data(), dias.get(dias.size() - 1).data(),
                null, null, dias.stream().mapToInt(DiaRelatorioRh::saldoSegundos).sum(), dias);
        RelatorioRh r = importar.receber(DONO, "relatorio.pdf", conteudo, USUARIO);
        conferir.processarRelatorio(r.id());
        return r;
    }

    private Divergencia divergencia(int dia) {
        return divergencias.naData(d(dia)).orElseThrow();
    }

    @BeforeEach
    void cenario() {
        local(21, "08:00:10", "12:00:05", "17:48:30");                // faltou a volta do almoço (falha no relógio)
        local(22, "08:00:00", "12:00:00", "13:00:00", "17:48:00");    // igual ao RH
        local(28, "08:00:00", "12:10:00", "13:00:00", "17:48:00");    // saída do almoço 10 min diferente
        enviar(LocalDateTime.of(2026, 8, 17, 14, 10), List.of(
                util(20, 0, "08:00:00", "12:00:00", "13:00:00", "17:48:00"),
                util(21, 0, "08:00:10", "12:00:05", "13:00:00", "17:48:30"),
                util(22, 0, "08:00:00", "12:00:00", "13:00:00", "17:48:00"),
                ocorrencia(23, "Feriado"),
                ocorrencia(24, "Férias"), ocorrencia(25, "Férias"), ocorrencia(26, "Férias"), ocorrencia(27, "Férias"),
                util(28, 0, "08:00:00", "12:00:00", "13:00:00", "17:48:00"),
                util(31, 0, "08:00:00", "12:00:00", "13:00:00", "17:48:00")), new byte[]{1});
    }

    @Test
    @DisplayName("Conferência do relatório: uma divergência por dia diferente, nada é alterado sozinho")
    void conferencia() {
        assertThat(divergencias.listar(DONO, StatusDivergencia.PENDENTE, null, null))
                .extracting(Divergencia::data, Divergencia::tipo)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(d(20), TipoDivergencia.SOMENTE_RH),
                        org.assertj.core.groups.Tuple.tuple(d(21), TipoDivergencia.BATIDA_FALTANDO),
                        org.assertj.core.groups.Tuple.tuple(d(23), TipoDivergencia.TIPO_DIA),
                        org.assertj.core.groups.Tuple.tuple(d(24), TipoDivergencia.TIPO_DIA),
                        org.assertj.core.groups.Tuple.tuple(d(27), TipoDivergencia.TIPO_DIA),
                        org.assertj.core.groups.Tuple.tuple(d(28), TipoDivergencia.HORARIO_DIFERENTE),
                        org.assertj.core.groups.Tuple.tuple(d(31), TipoDivergencia.SOMENTE_RH));
        assertThat(registros.quantidade()).isEqualTo(3);
        assertThat(relatorios.listar(DONO)).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(StatusRelatorioRh.CONCLUIDO);
            assertThat(r.divergencias()).isEqualTo(7);
        });
        assertThat(notificacoesRepo.listarRecentes(DONO, 5)).singleElement()
                .satisfies(n -> assertThat(n.titulo()).isEqualTo("Relatório do RH: 7 dia(s) com diferença"));
        assertThatThrownBy(() -> importar.receber(DONO, "de-novo.pdf", new byte[]{1}, USUARIO))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("já foi enviado");
    }

    @Test
    @DisplayName("Aceite em lote: histórico só do RH, feriado e férias (o período inteiro de uma vez)")
    void aceiteEmLote() {
        ResolverDivergenciaUseCase.ResultadoLote r = resolver.aceitarEmLote(DONO, 
                Set.of(TipoDivergencia.SOMENTE_RH, TipoDivergencia.TIPO_DIA), null, null, USUARIO, true);

        assertThat(r.falhas()).isEmpty();
        assertThat(r.aceitas()).isEqualTo(4); // 20, 23, 24 (leva 25 a 27 junto) e 31
        assertThat(registros.buscarPorData(DONO, d(20)).orElseThrow().getHorariosAjustados()).isEmpty();
        assertThat(datasFeriado).containsExactly(d(23));
        assertThat(ausenciasRepo.todas()).singleElement().satisfies(a -> {
            assertThat(a.tipo()).isEqualTo(TipoAusencia.FERIAS);
            assertThat(a.dataInicio()).isEqualTo(d(24));
            assertThat(a.dataFim()).isEqualTo(d(27));
        });
        assertThat(divergencia(27).status()).isEqualTo(StatusDivergencia.RESOLVIDA);
        assertThat(divergencia(27).observacao()).contains("Aceito junto com 24/07");
        assertThat(divergencias.listar(DONO, StatusDivergencia.PENDENTE, null, null)).extracting(Divergencia::data)
                .containsExactly(d(21), d(28));
    }

    @Test
    @DisplayName("Aceitar a batida que o RH corrigiu: o dia fecha igual ao RH e só a batida nova fica marcada")
    void aceitarBatidaFaltando() {
        Divergencia aceita = resolver.aceitarRh(DONO, divergencia(21).id(), USUARIO, true);

        assertThat(aceita.status()).isEqualTo(StatusDivergencia.ACEITO_RH);
        RegistroJornada dia = registros.buscarPorData(DONO, d(21)).orElseThrow();
        assertThat(dia.getSaldoDiarioSegundos()).isZero();
        assertThat(dia.getHorariosAjustados()).containsExactly(LocalTime.of(13, 0));
        assertThat(ajustes.listarPorData(DONO, d(21))).singleElement()
                .satisfies(a -> assertThat(a.justificativa()).contains("relatório do RH emitido em 17/08/2026"));
        assertThatThrownBy(() -> resolver.aceitarRh(DONO, aceita.id(), USUARIO, true)).isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Manter dados locais vale enquanto a situação não muda; se o dia mudar, volta a ficar pendente")
    void manterEReabrir() {
        Divergencia mantida = resolver.manterLocal(DONO, divergencia(28).id(), "Vou conferir com o RH", USUARIO);
        assertThat(mantida.status()).isEqualTo(StatusDivergencia.MANTIDO_LOCAL);

        conferir.conferirTudo();
        assertThat(divergencia(28).status()).isEqualTo(StatusDivergencia.MANTIDO_LOCAL);

        ajustar.executar(DONO, d(28), h("08:00:00", "12:05:00", "13:00:00", "17:48:00"), "Corrigido pelo RH", USUARIO);
        conferir.conferirDatas(DONO, Set.of(d(28)), "após ajuste");
        assertThat(divergencia(28).status()).isEqualTo(StatusDivergencia.PENDENTE);
        assertThat(divergencia(28).descricao()).contains("12:05:00");

        ajustar.executar(DONO, d(28), h("08:00:00", "12:00:00", "13:00:00", "17:48:00"), "Corrigido pelo RH", USUARIO);
        conferir.conferirDatas(DONO, Set.of(d(28)), "Igual ao RH após ajuste manual");
        assertThat(divergencia(28).status()).isEqualTo(StatusDivergencia.RESOLVIDA);
        assertThat(divergencia(28).observacao()).isEqualTo("Igual ao RH após ajuste manual");
    }

    @Test
    @DisplayName("Divergência que não dá para aceitar explica o motivo")
    void naoAceitavel() {
        enviar(LocalDateTime.of(2026, 8, 18, 9, 0), List.of(
                util(29, 61, "08:04:59", "12:00:20", "13:02:49", "17:49:01", "17:49:46")), new byte[]{2});

        assertThatThrownBy(() -> resolver.aceitarRh(DONO, divergencia(29).id(), USUARIO, true))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("ímpar");
    }

    @Test
    @DisplayName("Relatório mais novo prevalece; ao excluí-lo, o período volta a ser conferido com o anterior")
    void relatorioMaisNovo() {
        RelatorioRh novo = enviar(LocalDateTime.of(2026, 9, 14, 16, 0),
                List.of(util(28, 600, "08:00:00", "12:10:00", "13:00:00", "17:48:00")), new byte[]{3});
        assertThat(divergencia(28).status()).isEqualTo(StatusDivergencia.RESOLVIDA); // o RH corrigiu e agora bate

        importar.excluir(DONO, novo.id());
        assertThat(divergencia(28).status()).isEqualTo(StatusDivergencia.PENDENTE);
        assertThat(divergencia(28).relatorioId()).isNotEqualTo(novo.id());
    }

    @Test
    @DisplayName("Tela dividida: conferência atual à esquerda, RH à direita; resumo compara os saldos do período")
    void consulta() {
        var views = consultar.divergencias(DONO, StatusDivergencia.PENDENTE, d(21), d(21));
        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.local().marcacoes().stream().filter(m -> m.real() != null)).hasSize(3);
            assertThat(v.rh().horarios()).hasSize(4);
            assertThat(v.tipoDiaLocal()).isEqualTo(TipoDia.UTIL);
        });
        resolver.aceitarEmLote(DONO, Set.of(TipoDivergencia.values()), null, null, USUARIO, true);
        var comparativo = consultar.resumo(DONO).relatorios().get(0);
        assertThat(comparativo.saldoRhSegundos()).isZero();
        assertThat(comparativo.diasConferidos()).isEqualTo(10);
    }

    private static final Quem DONO_ADMIN = new Quem(true, true);
    private static final Quem DONO_COMUM = new Quem(true, false);

    private DivergenciaView.Tela tela(int dia, Quem quem) {
        return consultar.divergencias(DONO, null, d(dia), d(dia), quem).get(0).tela();
    }

    @Test
    @DisplayName("Tela: cada diferença vem escrita em palavras, com o tamanho da diferença e os dois lados marcados")
    void diferencaEmPalavras() {
        DivergenciaView.Tela faltando = tela(21, DONO_ADMIN);
        assertThat(faltando.dia()).isEqualTo("Terça, 21/07/2026");
        assertThat(faltando.tipo()).isEqualTo("Falta batida no sistema");
        assertThat(faltando.frase()).isEqualTo("Falta 1 batida no sistema: o RH tem 13:00 e o sistema não.");
        assertThat(faltando.situacao()).isEqualTo("Para decidir");
        assertThat(faltando.sistema().horarios()).extracting(DivergenciaView.Hora::texto, DivergenciaView.Hora::marca)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("08:00", Marca.IGUAL),
                        org.assertj.core.groups.Tuple.tuple("12:00", Marca.IGUAL),
                        org.assertj.core.groups.Tuple.tuple("17:48", Marca.IGUAL));
        assertThat(faltando.rh().horarios()).extracting(DivergenciaView.Hora::exato, DivergenciaView.Hora::marca)
                .contains(org.assertj.core.groups.Tuple.tuple("13:00:00", Marca.DIFERENTE));
        assertThat(faltando.rh().saldo()).isEqualTo("em dia");
        assertThat(faltando.motivoDoAjuste()).isEqualTo("Conforme relatório do RH emitido em 17/08/2026");

        DivergenciaView.Tela horario = tela(28, DONO_ADMIN);
        assertThat(horario.frase())
                .isEqualTo("1 batida com horário diferente: Saída p/ almoço (12:10 no sistema e 12:00 no RH).");
        assertThat(horario.impacto())
                .isEqualTo("Diferença de 10 min no saldo do dia: em dia no RH e + 10 min a favor no sistema.");
        assertThat(horario.sistema().saldo()).isEqualTo("+ 10 min a favor");
        assertThat(horario.sistema().tom()).isEqualTo(Tom.POSITIVO);
        assertThat(horario.sistema().trabalhado()).isEqualTo("8h 58min");

        // o mesmo horário com segundos diferentes é a mesma batida: sem marca, e os segundos não aparecem
        assertThat(faltando.sistema().horarios().get(0).exato()).isEqualTo("08:00:10");

        DivergenciaView.Tela ferias = tela(24, DONO_ADMIN);
        assertThat(ferias.tipo()).isEqualTo("Folga ou feriado diferente");
        assertThat(ferias.frase())
                .isEqualTo("O RH marcou este dia como férias. No sistema ele está como dia de trabalho, sem batidas.");
        assertThat(ferias.rh().titulo()).isEqualTo("Férias");
        assertThat(ferias.rh().saldo()).isEqualTo("não soma nem desconta");

        // batidas iguais com 1 s de diferença que muda o saldo: aí sim os segundos aparecem, marcados
        local(30, "08:00:01", "12:00:01", "13:00:01", "17:53:01");
        enviar(LocalDateTime.of(2026, 8, 18, 9, 0), List.of(
                util(30, 300, "08:00:00", "12:00:00", "13:00:00", "17:53:00")), new byte[]{4});
        DivergenciaView.Tela segundos = tela(30, DONO_ADMIN);
        assertThat(segundos.tipo()).isEqualTo("Segundos diferentes");
        assertThat(segundos.sistema().horarios()).extracting(DivergenciaView.Hora::texto, DivergenciaView.Hora::marca)
                .contains(org.assertj.core.groups.Tuple.tuple("17:53:01", Marca.SEGUNDOS));
        assertThat(segundos.impacto()).isEqualTo("Diferença de 1 s no saldo do dia.");
    }

    @Test
    @DisplayName("Tela: dia sem registro ou com batida faltando fica fora do saldo — a frase nunca diz que desconta")
    void diaForaDoSaldoNaoDesconta() {
        DivergenciaView.Tela semRegistro = tela(20, DONO_ADMIN);
        assertThat(semRegistro.frase()).isEqualTo("O RH tem 4 batidas neste dia e o sistema não tem nenhum registro.");
        assertThat(semRegistro.impacto()).isEqualTo("No sistema o dia não tem registro: fica fora do saldo até ser "
                + "corrigido (não desconta nada). No RH o dia não soma nem desconta.");
        assertThat(semRegistro.sistema().titulo()).isEqualTo("Dia de trabalho, sem batidas");
        assertThat(semRegistro.sistema().saldo()).isEqualTo("fora do saldo até ser corrigido");
        assertThat(semRegistro.sistema().tom()).isEqualTo(Tom.ATENCAO);
        // o outro lado não tem batidas: não há o que marcar como diferente
        assertThat(semRegistro.rh().horarios()).hasSize(4).allSatisfy(h -> assertThat(h.marca()).isNull());

        DivergenciaView.Tela faltaBatida = tela(21, DONO_ADMIN);
        assertThat(faltaBatida.impacto()).startsWith("No sistema falta batida neste dia: ele fica fora do saldo até "
                + "ser corrigido (não desconta nada).");
        assertThat(faltaBatida.sistema().saldo()).isEqualTo("falta batida: fora do saldo até ser corrigido");

        assertThat(consultar.divergencias(DONO, null, null, null, DONO_ADMIN))
                .allSatisfy(v -> assertThat(v.tela().frase() + v.tela().impacto()).doesNotContain("descontando"));
    }

    @Test
    @DisplayName("Tela: os botões de cada diferença vêm decididos (quem só consulta não tem nenhum; feriado é do administrador)")
    void botoesDaDiferenca() {
        assertThat(consultar.divergencias(DONO, StatusDivergencia.PENDENTE, null, null))
                .allSatisfy(v -> {
                    assertThat(v.tela().acoes()).isEqualTo(DivergenciaView.Acoes.NENHUMA);
                    assertThat(v.tela().porQueNaoUsarRh()).isNull();
                });

        DivergenciaView.Acoes batida = tela(21, DONO_COMUM).acoes();
        assertThat(batida.usarRh()).isTrue();
        assertThat(batida.manter()).isTrue();
        assertThat(batida.ajustar()).isTrue();
        assertThat(batida.folgas()).isFalse();
        assertThat(batida.reabrir()).isFalse();
        assertThat(tela(21, DONO_COMUM).aoUsarRh()).contains("nunca é apagada");

        DivergenciaView.Tela feriadoComum = tela(23, DONO_COMUM);
        assertThat(feriadoComum.acoes().usarRh()).isFalse();
        assertThat(feriadoComum.acoes().folgas()).isTrue();
        assertThat(feriadoComum.acoes().ajustar()).isFalse();
        assertThat(feriadoComum.porQueNaoUsarRh()).contains("peça ao administrador");
        DivergenciaView.Tela feriadoAdmin = tela(23, DONO_ADMIN);
        assertThat(feriadoAdmin.acoes().usarRh()).isTrue();
        assertThat(feriadoAdmin.aoUsarRh()).contains("vira feriado").contains("todas as pessoas");
        assertThat(tela(24, DONO_COMUM).acoes().usarRh()).isTrue();
        assertThat(tela(24, DONO_COMUM).aoUsarRh()).contains("férias");

        enviar(LocalDateTime.of(2026, 8, 18, 9, 0), List.of(
                util(29, 61, "08:04:59", "12:00:20", "13:02:49", "17:49:01", "17:49:46")), new byte[]{2});
        DivergenciaView.Tela impar = tela(29, DONO_ADMIN);
        assertThat(impar.acoes().usarRh()).isFalse();
        assertThat(impar.acoes().ajustar()).isTrue();
        assertThat(impar.porQueNaoUsarRh()).contains("número ímpar de batidas (5)").contains("Ajuste as batidas à mão");
        assertThat(impar.aoUsarRh()).isNull();

        resolver.manterLocal(DONO, divergencia(28).id(), "Vou conferir com o RH", USUARIO);
        DivergenciaView.Tela mantida = tela(28, DONO_COMUM);
        assertThat(mantida.situacao()).isEqualTo("Mantido como está no sistema");
        assertThat(mantida.acoes()).isEqualTo(new DivergenciaView.Acoes(false, false, false, false, true));
        assertThat(consultar.decididas(DONO, null, null, DONO_COMUM)).extracting(v -> v.divergencia().data())
                .containsExactly(d(28));
    }

    @Test
    @DisplayName("Resumo: em quantos dias o RH e o sistema batem, numa frase, e quantas diferenças dá para resolver de uma vez")
    void resultadoDaComparacao() {
        ConciliacaoResumoView resumo = consultar.resumo(DONO);
        assertThat(resumo.comparacao()).isEqualTo(new ConciliacaoResumoView.Comparacao(10, 3, 7, 0));
        assertThat(Textos.comparacao(resumo.comparacao()))
                .isEqualTo("O RH e o sistema batem em 3 dos 10 dias conferidos. 7 dias têm diferença para decidir.");
        assertThat(resumo.aceitaveisPorTipo()).containsOnly(
                java.util.Map.entry(TipoDivergencia.SOMENTE_RH, 2), java.util.Map.entry(TipoDivergencia.TIPO_DIA, 3),
                java.util.Map.entry(TipoDivergencia.BATIDA_FALTANDO, 1),
                java.util.Map.entry(TipoDivergencia.HORARIO_DIFERENTE, 1));
        assertThat(resumo.decididas()).isZero();
        assertThat(resumo.total()).isEqualTo(7);

        ConciliacaoResumoView.Comparativo comparativo = resumo.relatorios().get(0);
        assertThat(comparativo.diasDiferentes()).isEqualTo(7);
        Textos.Relatorio relatorio = Textos.relatorio(comparativo.relatorio(), comparativo);
        assertThat(relatorio.periodo()).isEqualTo("20/07/2026 a 31/07/2026");
        assertThat(relatorio.emitido()).isEqualTo("emitido pelo RH em 17/08/2026 às 14:10");
        assertThat(relatorio.situacao()).isEqualTo("Comparado");
        assertThat(relatorio.diasIguais()).isEqualTo("3 de 10 dias iguais");
        assertThat(relatorio.saldoRh()).isEqualTo("em dia");
        assertThat(relatorio.saldoSistema()).isEqualTo("+ 10 min a favor");
        assertThat(relatorio.diferenca()).isEqualTo("10 min de diferença");
        assertThat(relatorio.saldosIguais()).isFalse();
        assertThat(relatorio.foraDoSaldo()).isEqualTo(
                "1 dia com batida faltando fica fora do saldo do sistema até ser corrigido (não desconta nada).");

        resolver.manterLocal(DONO, divergencia(28).id(), null, USUARIO);
        resolver.aceitarEmLote(DONO, Set.of(TipoDivergencia.values()), null, null, USUARIO, true);
        ConciliacaoResumoView depois = consultar.resumo(DONO);
        assertThat(depois.comparacao()).isEqualTo(new ConciliacaoResumoView.Comparacao(10, 9, 0, 1));
        assertThat(Textos.comparacao(depois.comparacao())).isEqualTo("O RH e o sistema batem em 9 dos 10 dias "
                + "conferidos. 1 dia ficou diferente por decisão (mantido como está no sistema).");
        assertThat(depois.decididas()).isEqualTo(7);
        assertThat(Textos.comparacao(ConciliacaoResumoView.Comparacao.NADA)).isNull();
        assertThat(Textos.comparacao(new ConciliacaoResumoView.Comparacao(22, 22, 0, 0)))
                .isEqualTo("O RH e o sistema batem em todos os 22 dias conferidos.");
    }
}
