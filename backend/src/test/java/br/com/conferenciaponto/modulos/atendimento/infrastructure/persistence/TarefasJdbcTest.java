package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** A fila em atendimento.tarefa, no PostgreSQL temporário (FOR UPDATE SKIP LOCKED de verdade). */
class TarefasJdbcTest {

    private static final Duration PRAZO = Duration.ofMinutes(15);

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Tarefas tarefas = new TarefasJdbc(jdbc, RepositoriosDeTeste.transacao());
    /** No futuro, para as tarefas dos outros testes (criadas "agora") não atrapalharem as contas. */
    private final Instant agora = Instant.now().plus(3650, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);

    /** A fila começa vazia em cada teste (as varreduras pegam qualquer tarefa pronta do banco). */
    @BeforeEach
    void esvaziarAFila() {
        jdbc.sql("DELETE FROM atendimento.tarefa").update();
    }

    /** Um atendimento novo de um usuário novo, com {@code n} arquivos, e as tarefas "baixar" de cada um. */
    private List<UUID> atendimentoComTarefas(UUID usuario, int n) {
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
            tarefas.criar(TipoDeTarefa.BAIXAR, atendimento, arquivo, null, 3, Instant.now());
            arquivos.add(arquivo);
        }
        jdbc.sql("UPDATE atendimento.tarefa SET executar_apos = ? WHERE atendimento_id = ?")
                .params(java.time.OffsetDateTime.ofInstant(agora.minusSeconds(1), java.time.ZoneOffset.UTC), atendimento).update();
        arquivos.add(0, atendimento);
        return arquivos;
    }

    private List<Tarefa> pegar(int limite, int porUsuario) {
        return tarefas.pegar(limite, porUsuario, Map.of(), agora, PRAZO).stream()
                .filter(t -> meus.contains(t.atendimentoId())).toList();
    }

    private final Set<UUID> meus = Collections.synchronizedSet(new HashSet<>());

    private UUID novo(int arquivos) {
        List<UUID> criado = atendimentoComTarefas(PostgresDeTeste.novoUsuario(true), arquivos);
        meus.add(criado.get(0));
        return criado.get(0);
    }

    private String situacao(long id) {
        return jdbc.sql("SELECT situacao FROM atendimento.tarefa WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void naoCriaATarefaRepetida() {
        List<UUID> criado = atendimentoComTarefas(PostgresDeTeste.novoUsuario(true), 1);

        assertThat(tarefas.criar(TipoDeTarefa.BAIXAR, criado.get(0), criado.get(1), null, 3, Instant.now())).isFalse();
        assertThat(tarefas.criar(TipoDeTarefa.ANALISAR, criado.get(0), criado.get(1), null, 3, Instant.now())).isTrue();
        assertThat(tarefas.ativas(criado.get(0))).isEqualTo(2);
    }

    @Test
    void pegaMarcaComoEmExecucaoComPrazoENaoEntregaDuasVezes() {
        UUID atendimento = novo(2);

        List<Tarefa> primeira = pegar(10, 10);
        List<Tarefa> segunda = pegar(10, 10);

        assertThat(primeira).hasSize(2).allMatch(t -> t.tentativa() == 1 && t.maxTentativas() == 3)
                .allMatch(t -> t.atendimentoId().equals(atendimento));
        assertThat(segunda).isEmpty();
        Instant prazo = jdbc.sql("SELECT max(em_execucao_ate) FROM atendimento.tarefa WHERE atendimento_id = ?")
                .param(atendimento).query(java.time.OffsetDateTime.class).single().toInstant();
        assertThat(prazo).isEqualTo(agora.plus(PRAZO));
        assertThat(situacao(primeira.get(0).id())).isEqualTo("executando");
    }

    @Test
    void duasVarredurasAoMesmoTempoNuncaPegamAMesmaTarefa() throws Exception {
        novo(30);
        ExecutorService duas = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<List<Tarefa>>> resultados = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                resultados.add(duas.submit(() -> {
                    largada.await();
                    List<Tarefa> todas = new ArrayList<>();
                    for (int vez = 0; vez < 5; vez++) {
                        todas.addAll(pegar(4, 100));
                    }
                    return todas;
                }));
            }
            largada.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<List<Tarefa>> r : resultados) {
                r.get().forEach(t -> ids.add(t.id()));
            }
            assertThat(ids).as("pegas ao mesmo tempo").isNotEmpty().doesNotHaveDuplicates();
            // a que travou as candidatas faz a outra pular: o resto sai nas varreduras seguintes, sem repetir
            for (List<Tarefa> mais = pegar(4, 100); !mais.isEmpty(); mais = pegar(4, 100)) {
                mais.forEach(t -> ids.add(t.id()));
            }
            assertThat(ids).doesNotHaveDuplicates().hasSize(30);
        } finally {
            duas.shutdownNow();
        }
    }

    @Test
    void respeitaOLimitePorPessoaEPorTipoContandoOQueJaEstaEmExecucao() {
        UUID deA = novo(6);
        UUID deB = novo(2);

        List<Tarefa> pegas = pegar(8, 4);

        assertThat(pegas.stream().filter(t -> t.atendimentoId().equals(deA))).hasSize(4);
        assertThat(pegas.stream().filter(t -> t.atendimentoId().equals(deB))).hasSize(2);
        assertThat(pegar(8, 4)).as("A já tem 4 em execução").isEmpty();

        novo(5);
        assertThat(tarefas.pegar(8, 10, Map.of(TipoDeTarefa.BAIXAR, 7), agora, PRAZO))
                .as("6 downloads já em execução e o limite do tipo é 7").hasSize(1);
    }

    @Test
    void adiadaSoVoltaDepoisDaEsperaEConcluidaSai() {
        novo(2);
        List<Tarefa> pegas = pegar(10, 10);

        tarefas.adiar(pegas.get(0).id(), agora.plusSeconds(60), "DOWNLOAD_FALHOU", "rede", agora);
        tarefas.concluir(pegas.get(1).id(), agora);

        assertThat(pegar(10, 10)).isEmpty();
        assertThat(tarefas.pegar(10, 10, Map.of(), agora.plusSeconds(61), PRAZO))
                .filteredOn(t -> t.id() == pegas.get(0).id()).singleElement()
                .extracting(Tarefa::tentativa).isEqualTo(2);
        assertThat(situacao(pegas.get(1).id())).isEqualTo("concluida");
    }

    @Test
    void tarefaPresaAlemDoPrazoVoltaParaAFila() {
        novo(1);
        Tarefa pega = pegar(10, 10).get(0);

        assertThat(tarefas.devolverVencidas(agora.plus(PRAZO).minusSeconds(1))).isZero();
        tarefas.renovar(List.of(pega.id()), agora.plus(PRAZO).plusSeconds(30));
        assertThat(tarefas.devolverVencidas(agora.plus(PRAZO).plusSeconds(1))).isZero();
        assertThat(tarefas.devolverVencidas(agora.plus(PRAZO).plusSeconds(31))).isGreaterThanOrEqualTo(1);
        assertThat(situacao(pega.id())).isEqualTo("pendente");
    }

    @Test
    void naSubidaOQueEstavaEmExecucaoVoltaParaAFila() {
        novo(1);
        Tarefa pega = pegar(10, 10).get(0);

        tarefas.devolverTodasEmExecucao();

        assertThat(situacao(pega.id())).isEqualTo("pendente");
        assertThat(pegar(10, 10)).singleElement().extracting(Tarefa::tentativa).isEqualTo(2);
    }

    @Test
    void cancelarPausarERetomarOAtendimento() {
        UUID atendimento = novo(3);
        Tarefa emExecucao = pegar(1, 10).get(0);

        assertThat(tarefas.pausarDoAtendimento(atendimento, agora)).isEqualTo(2);
        assertThat(pegar(10, 10)).isEmpty();
        tarefas.pausar(emExecucao.id(), "DISCO_CHEIO", "cheio", agora);
        assertThat(situacao(emExecucao.id())).isEqualTo("pausada");
        assertThat(tarefas.retomarDoAtendimento(atendimento, agora)).isEqualTo(3);
        Tarefa denovo = pegar(10, 10).stream().filter(t -> t.id() == emExecucao.id()).findFirst().orElseThrow();
        assertThat(denovo.tentativa()).as("a pausa não gasta tentativa").isEqualTo(1);

        assertThat(tarefas.cancelarDoAtendimento(atendimento, agora)).isZero(); // as três estão em execução
        tarefas.adiar(denovo.id(), agora, "X", "y", agora);
        assertThat(tarefas.cancelarDoAtendimento(atendimento, agora)).isEqualTo(1);
        assertThat(situacao(denovo.id())).isEqualTo("cancelada");
        assertThat(tarefas.ativas(atendimento)).isEqualTo(2);
    }
}
