package br.com.conferenciaponto.modulos.atendimento.application.fila;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.AcompanhamentoDaFila;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** O motor da fila com o banco de verdade e executores de mentira. */
class CoordenadorDaFilaTest {

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final List<String> terminadas = new CopyOnWriteArrayList<>();
    private final List<CoordenadorDaFila> coordenadores = new ArrayList<>();

    private final AcompanhamentoDaFila acompanhamento = new AcompanhamentoDaFila() {
        @Override
        public void aoComecar(Tarefa tarefa) {
        }

        @Override
        public void aoTerminar(Tarefa tarefa, ResultadoDaTarefa resultado) {
            terminadas.add(tarefa.id() + ":" + resultado.getClass().getSimpleName());
        }
    };

    @BeforeEach
    void esvaziarAFila() {
        jdbc.sql("DELETE FROM atendimento.tarefa").update();
    }

    @AfterEach
    void parar() {
        coordenadores.forEach(CoordenadorDaFila::parar);
    }

    /** Um executor de "baixar" que responde o que a função mandar. */
    private static ExecutorDeTarefa baixar(int limite, Function<Tarefa, ResultadoDaTarefa> resposta) {
        return new ExecutorDeTarefa() {
            @Override
            public TipoDeTarefa tipo() {
                return TipoDeTarefa.BAIXAR;
            }

            @Override
            public int limiteSimultaneo() {
                return limite;
            }

            @Override
            public ResultadoDaTarefa executar(Tarefa tarefa) {
                return resposta.apply(tarefa);
            }
        };
    }

    private CoordenadorDaFila coordenador(int trabalhadores, int porUsuario, ExecutorDeTarefa... executores) {
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(executores), acompanhamento, Clock.systemUTC(),
                trabalhadores, porUsuario, Duration.ofMinutes(15), Duration.ofMillis(200), Duration.ofMillis(1),
                Duration.ofMillis(5), true);
        coordenadores.add(c);
        return c;
    }

    /** Atendimento novo (de um usuário novo) com {@code n} arquivos e uma tarefa "baixar" para cada um. */
    private List<UUID> atendimento(int n, int maxTentativas) {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        UUID atendimento = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em)
                        VALUES (?, ?, '1', '{}'::jsonb, 1, now(), now())
                        """).params(atendimento, usuario).update();
        List<UUID> arquivos = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            UUID arquivo = UUID.randomUUID();
            jdbc.sql("INSERT INTO atendimento.arquivo (id, atendimento_id, origem, ordem, nome_original) VALUES (?, ?, 'anexo_conversa', ?, 'a')")
                    .params(arquivo, atendimento, i).update();
            tarefas.criar(TipoDeTarefa.BAIXAR, atendimento, arquivo, null, maxTentativas, Instant.now());
            arquivos.add(arquivo);
        }
        arquivos.add(0, atendimento);
        return arquivos;
    }

    private Map<String, Integer> situacoes() {
        Map<String, Integer> contagem = new ConcurrentHashMap<>();
        jdbc.sql("SELECT situacao, count(*) AS n FROM atendimento.tarefa GROUP BY situacao")
                .query(rs -> {
                    contagem.put(rs.getString("situacao"), rs.getInt("n"));
                });
        return contagem;
    }

    @Test
    void aFalhaDeUmItemNaoParaOsOutros() {
        List<UUID> criado = atendimento(40, 3);
        UUID quebrado = criado.get(17);
        CoordenadorDaFila coordenador = coordenador(8, 8, baixar(0, t -> t.arquivoId().equals(quebrado)
                ? ResultadoDaTarefa.definitiva("DOWNLOAD_RECUSADO", "recusado") : ResultadoDaTarefa.concluida()));

        coordenador.varrer();

        await().atMost(20, TimeUnit.SECONDS).until(() -> terminadas.size() == 40);
        assertThat(situacoes()).containsEntry("concluida", 39).containsEntry("falhou", 1);
        assertThat(terminadas).filteredOn(t -> t.endsWith("FalhaDefinitiva")).hasSize(1);
    }

    @Test
    void falhaPassageiraVoltaParaAFilaENaUltimaTentativaViraDefinitiva() {
        atendimento(1, 2);
        AtomicInteger vezes = new AtomicInteger();
        CoordenadorDaFila coordenador = coordenador(2, 2, baixar(0, t -> {
            vezes.incrementAndGet();
            return ResultadoDaTarefa.temporaria("DOWNLOAD_FALHOU", "rede");
        }));

        coordenador.varrer();

        await().atMost(10, TimeUnit.SECONDS).until(() -> terminadas.size() == 2);
        assertThat(vezes).hasValue(2);
        assertThat(terminadas.get(0)).endsWith("FalhaTemporaria");
        assertThat(terminadas.get(1)).endsWith("FalhaDefinitiva");
        assertThat(situacoes()).containsEntry("falhou", 1);
        assertThat(jdbc.sql("SELECT erro_codigo FROM atendimento.tarefa").query(String.class).single()).isEqualTo("DOWNLOAD_FALHOU");
    }

    @Test
    void respeitaOLimitePorPessoaEPorTipo() throws InterruptedException {
        List<UUID> deA = atendimento(6, 3);
        atendimento(6, 3);
        CountDownLatch solta = new CountDownLatch(1);
        Map<UUID, AtomicInteger> agoraPorAtendimento = new ConcurrentHashMap<>();
        AtomicInteger agora = new AtomicInteger();
        AtomicInteger maximoTotal = new AtomicInteger();
        Map<UUID, Integer> maximoPorAtendimento = new ConcurrentHashMap<>();
        CoordenadorDaFila coordenador = coordenador(8, 2, baixar(3, t -> {
            int total = agora.incrementAndGet();
            int doAtendimento = agoraPorAtendimento.computeIfAbsent(t.atendimentoId(), k -> new AtomicInteger()).incrementAndGet();
            maximoTotal.accumulateAndGet(total, Math::max);
            maximoPorAtendimento.merge(t.atendimentoId(), doAtendimento, Math::max);
            try {
                solta.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            agoraPorAtendimento.get(t.atendimentoId()).decrementAndGet();
            agora.decrementAndGet();
            return ResultadoDaTarefa.concluida();
        }));

        assertThat(coordenador.varrer()).as("3 downloads de cada vez, 2 por pessoa").isEqualTo(3);
        assertThat(coordenador.varrer()).isZero();
        await().atMost(5, TimeUnit.SECONDS).until(() -> agora.get() == 3);
        solta.countDown();

        await().atMost(20, TimeUnit.SECONDS).until(() -> terminadas.size() == 12);
        assertThat(maximoTotal.get()).isLessThanOrEqualTo(3);
        assertThat(maximoPorAtendimento.values()).allMatch(m -> m <= 2);
        assertThat(maximoPorAtendimento).containsKey(deA.get(0));
    }

    @Test
    void excecaoInesperadaViraFalhaPassageiraSemOConteudoNoLog() {
        atendimento(1, 1);
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> linhas = new ListAppender<>();
        linhas.start();
        logger.addAppender(linhas);
        try {
            CoordenadorDaFila coordenador = coordenador(1, 1, baixar(0, t -> {
                throw new IllegalStateException("texto secreto da conversa");
            }));
            coordenador.varrer();
            await().atMost(10, TimeUnit.SECONDS).until(() -> terminadas.size() == 1);
        } finally {
            logger.detachAppender(linhas);
        }

        assertThat(situacoes()).containsEntry("falhou", 1); // era a única tentativa
        assertThat(jdbc.sql("SELECT erro_codigo FROM atendimento.tarefa").query(String.class).single()).isEqualTo("ERRO_INTERNO");
        assertThat(linhas.list).anySatisfy(l -> assertThat(l.getFormattedMessage()).contains("IllegalStateException"));
        assertThat(linhas.list).allSatisfy(l -> {
            assertThat(l.getFormattedMessage()).doesNotContain("texto secreto");
            assertThat(l.getThrowableProxy()).isNull();
        });
    }

    @Test
    void pausarDeixaATarefaPausadaSemGastarTentativa() {
        atendimento(1, 3);
        CoordenadorDaFila coordenador = coordenador(1, 1, baixar(0, t -> ResultadoDaTarefa.pausar("DISCO_CHEIO", "cheio")));

        coordenador.varrer();

        await().atMost(10, TimeUnit.SECONDS).until(() -> terminadas.size() == 1);
        assertThat(terminadas.get(0)).endsWith("Pausar");
        assertThat(situacoes()).containsEntry("pausada", 1);
        assertThat(jdbc.sql("SELECT tentativas FROM atendimento.tarefa").query(Integer.class).single()).isZero();
    }

    @Test
    void tarefaCanceladaNaoRodaETipoSemExecutorFalha() {
        List<UUID> criado = atendimento(3, 3);
        tarefas.cancelarDoAtendimento(criado.get(0), Instant.now());
        List<UUID> outro = atendimento(1, 3);
        jdbc.sql("UPDATE atendimento.tarefa SET tipo = 'redigir' WHERE atendimento_id = ?").param(outro.get(0)).update();
        AtomicInteger vezes = new AtomicInteger();
        CoordenadorDaFila coordenador = coordenador(4, 4, baixar(0, t -> {
            vezes.incrementAndGet();
            return ResultadoDaTarefa.concluida();
        }));

        coordenador.varrer();

        await().atMost(10, TimeUnit.SECONDS).until(() -> terminadas.size() == 1);
        assertThat(vezes).hasValue(0);
        assertThat(situacoes()).containsEntry("cancelada", 3).containsEntry("falhou", 1);
        assertThat(jdbc.sql("SELECT erro_codigo FROM atendimento.tarefa WHERE situacao = 'falhou'").query(String.class).single())
                .isEqualTo("TAREFA_SEM_EXECUTOR");
    }

    @Test
    void depoisDeParadoNaoPegaMaisNenhumaTarefa() {
        atendimento(2, 3);
        AtomicInteger vezes = new AtomicInteger();
        CoordenadorDaFila c = coordenador(4, 4, baixar(0, t -> {
            vezes.incrementAndGet();
            return ResultadoDaTarefa.concluida();
        }));

        c.parar();

        assertThat(c.varrer()).isZero();
        assertThat(vezes).hasValue(0);
        assertThat(situacoes()).containsOnly(Map.entry("pendente", 2));
    }

    @Test
    void depoisDeReiniciarOQueEstavaEmExecucaoRodaDeNovo() {
        atendimento(2, 3);
        assertThat(tarefas.pegar(10, 10, Map.of(), Instant.now(), Duration.ofMinutes(15))).hasSize(2); // e o serviço parou
        AtomicInteger vezes = new AtomicInteger();
        CoordenadorDaFila novo = coordenador(4, 4, baixar(0, t -> {
            vezes.incrementAndGet();
            return ResultadoDaTarefa.concluida();
        }));

        novo.iniciar();

        await().atMost(15, TimeUnit.SECONDS).until(() -> terminadas.size() == 2);
        assertThat(vezes).hasValue(2);
        assertThat(situacoes()).containsEntry("concluida", 2);
    }

    @Test
    void esperaCrescenteComLimiteERespeitandoOPedido() {
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(), acompanhamento, Clock.systemUTC(), 1, 1,
                Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMinutes(10), false);

        assertThat(c.espera(1, null)).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(12));
        assertThat(c.espera(2, null)).isBetween(Duration.ofSeconds(20), Duration.ofSeconds(24));
        assertThat(c.espera(4, null)).isBetween(Duration.ofSeconds(80), Duration.ofSeconds(96));
        assertThat(c.espera(30, null)).isEqualTo(Duration.ofMinutes(10));
        assertThat(c.espera(1, Duration.ofMinutes(3))).isEqualTo(Duration.ofMinutes(3));
        c.parar();
    }
}
