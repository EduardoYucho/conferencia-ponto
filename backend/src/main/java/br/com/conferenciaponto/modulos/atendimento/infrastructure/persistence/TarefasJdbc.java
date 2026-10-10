package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A fila em atendimento.tarefa. Quem pega as tarefas usa {@code FOR UPDATE SKIP LOCKED}: duas varreduras ao mesmo
 * tempo nunca entregam a mesma tarefa. Os limites por pessoa e por tipo contam também o que já está em execução.
 */
@Repository
class TarefasJdbc implements Tarefas {

    /** Situações em que a tarefa ainda vai (ou pode voltar a) rodar. */
    private static final String ATIVA = "('pendente', 'executando', 'pausada')";

    private final JdbcClient jdbc;
    private final TransactionOperations transacao;

    TarefasJdbc(JdbcClient jdbc, TransactionOperations transacao) {
        this.jdbc = jdbc;
        this.transacao = transacao;
    }

    @Override
    public boolean criar(TipoDeTarefa tipo, UUID atendimentoId, UUID arquivoId, UUID saidaId, int maxTentativas,
                         Instant agora) {
        return jdbc.sql("""
                        INSERT INTO atendimento.tarefa (atendimento_id, arquivo_id, saida_id, tipo, max_tentativas,
                                                        executar_apos, criada_em, atualizada_em)
                        SELECT :atendimento, :arquivo, :saida, :tipo, :max, :agora, :agora, :agora
                         WHERE NOT EXISTS (SELECT 1 FROM atendimento.tarefa
                                            WHERE tipo = :tipo AND atendimento_id = :atendimento
                                              AND arquivo_id IS NOT DISTINCT FROM :arquivo
                                              AND saida_id IS NOT DISTINCT FROM :saida
                                              AND situacao IN %s)
                        """.formatted(ATIVA))
                .param("atendimento", atendimentoId)
                .param("arquivo", arquivoId)
                .param("saida", saidaId)
                .param("tipo", tipo.codigo())
                .param("max", (short) Math.max(1, maxTentativas))
                .param("agora", utc(agora))
                .update() > 0;
    }

    @Override
    public List<Tarefa> pegar(int limite, int porUsuario, Map<TipoDeTarefa, Integer> porTipo, Instant agora,
                              Duration prazo) {
        if (limite <= 0) {
            return List.of();
        }
        List<Tarefa> pegas = transacao.execute(status -> {
            List<Tarefa> candidatas = jdbc.sql("""
                            SELECT t.id, t.tipo, t.atendimento_id, a.usuario_id, t.arquivo_id, t.saida_id, t.tentativas,
                                   t.max_tentativas
                              FROM atendimento.tarefa t
                              JOIN atendimento.atendimento a ON a.id = t.atendimento_id
                             WHERE t.situacao = 'pendente' AND t.executar_apos <= :agora
                             ORDER BY t.executar_apos, t.id
                             LIMIT :candidatas
                               FOR UPDATE OF t SKIP LOCKED
                            """)
                    .param("agora", utc(agora))
                    .param("candidatas", Math.max(50, limite * 10))
                    .query((rs, linha) -> new Tarefa(rs.getLong("id"), TipoDeTarefa.doCodigo(rs.getString("tipo")),
                            rs.getObject("atendimento_id", UUID.class), rs.getObject("usuario_id", UUID.class),
                            rs.getObject("arquivo_id", UUID.class), rs.getObject("saida_id", UUID.class),
                            rs.getInt("tentativas") + 1, rs.getInt("max_tentativas")))
                    .list();
            if (candidatas.isEmpty()) {
                return List.<Tarefa>of();
            }
            Map<UUID, Integer> doUsuario = new HashMap<>();
            Map<TipoDeTarefa, Integer> doTipo = new HashMap<>();
            jdbc.sql("""
                            SELECT a.usuario_id, t.tipo, count(*) AS n
                              FROM atendimento.tarefa t JOIN atendimento.atendimento a ON a.id = t.atendimento_id
                             WHERE t.situacao = 'executando'
                             GROUP BY a.usuario_id, t.tipo
                            """)
                    .query(rs -> {
                        int n = rs.getInt("n");
                        doUsuario.merge(rs.getObject("usuario_id", UUID.class), n, Integer::sum);
                        doTipo.merge(TipoDeTarefa.doCodigo(rs.getString("tipo")), n, Integer::sum);
                    });
            List<Tarefa> escolhidas = new ArrayList<>();
            for (Tarefa t : candidatas) {
                if (escolhidas.size() >= limite) {
                    break;
                }
                int limiteDoTipo = porTipo.getOrDefault(t.tipo(), 0);
                if (doUsuario.getOrDefault(t.usuarioId(), 0) >= porUsuario
                        || limiteDoTipo > 0 && doTipo.getOrDefault(t.tipo(), 0) >= limiteDoTipo) {
                    continue;
                }
                doUsuario.merge(t.usuarioId(), 1, Integer::sum);
                doTipo.merge(t.tipo(), 1, Integer::sum);
                escolhidas.add(t);
            }
            if (!escolhidas.isEmpty()) {
                jdbc.sql("""
                                UPDATE atendimento.tarefa
                                   SET situacao = 'executando', em_execucao_ate = :ate, tentativas = tentativas + 1,
                                       atualizada_em = :agora
                                 WHERE id IN (:ids)
                                """)
                        .param("ate", utc(agora.plus(prazo)))
                        .param("agora", utc(agora))
                        .param("ids", escolhidas.stream().map(Tarefa::id).toList())
                        .update();
            }
            return escolhidas;
        });
        return pegas == null ? List.of() : pegas;
    }

    @Override
    public void renovar(Collection<Long> ids, Instant ate) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE atendimento.tarefa SET em_execucao_ate = :ate WHERE id IN (:ids) AND situacao = 'executando'")
                .param("ate", utc(ate))
                .param("ids", List.copyOf(ids))
                .update();
    }

    @Override
    public int devolverVencidas(Instant agora) {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'pendente', em_execucao_ate = NULL, atualizada_em = :agora
                         WHERE situacao = 'executando' AND em_execucao_ate < :agora
                        """)
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int devolverTodasEmExecucao() {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'pendente', em_execucao_ate = NULL, atualizada_em = now()
                         WHERE situacao = 'executando'
                        """)
                .update();
    }

    @Override
    public void concluir(long id, Instant agora) {
        terminar(id, "concluida", null, null, agora);
    }

    @Override
    public void adiar(long id, Instant executarApos, String codigo, String mensagem, Instant agora) {
        jdbc.sql("""
                        UPDATE atendimento.tarefa
                           SET situacao = 'pendente', em_execucao_ate = NULL, executar_apos = :apos,
                               erro_codigo = :codigo, erro_mensagem = :mensagem, atualizada_em = :agora
                         WHERE id = :id AND situacao = 'executando'
                        """)
                .param("id", id)
                .param("apos", utc(executarApos))
                .param("codigo", cortar(codigo, 40))
                .param("mensagem", cortar(mensagem, 500))
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public void falhar(long id, String codigo, String mensagem, Instant agora) {
        terminar(id, "falhou", codigo, mensagem, agora);
    }

    @Override
    public void pausar(long id, String codigo, String mensagem, Instant agora) {
        jdbc.sql("""
                        UPDATE atendimento.tarefa
                           SET situacao = 'pausada', em_execucao_ate = NULL, tentativas = greatest(tentativas - 1, 0),
                               erro_codigo = :codigo, erro_mensagem = :mensagem, atualizada_em = :agora
                         WHERE id = :id AND situacao = 'executando'
                        """)
                .param("id", id)
                .param("codigo", cortar(codigo, 40))
                .param("mensagem", cortar(mensagem, 500))
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int cancelarDoAtendimento(UUID atendimentoId, Instant agora) {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'cancelada', atualizada_em = :agora
                         WHERE atendimento_id = :atendimento AND situacao IN ('pendente', 'pausada')
                        """)
                .param("atendimento", atendimentoId)
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int pausarDoAtendimento(UUID atendimentoId, Instant agora) {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'pausada', atualizada_em = :agora
                         WHERE atendimento_id = :atendimento AND situacao = 'pendente'
                        """)
                .param("atendimento", atendimentoId)
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int retomarDoAtendimento(UUID atendimentoId, Instant agora) {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'pendente', executar_apos = :agora, atualizada_em = :agora
                         WHERE atendimento_id = :atendimento AND situacao = 'pausada'
                        """)
                .param("atendimento", atendimentoId)
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int cancelarDoArquivo(UUID arquivoId, Instant agora) {
        return jdbc.sql("""
                        UPDATE atendimento.tarefa SET situacao = 'cancelada', atualizada_em = :agora
                         WHERE arquivo_id = :arquivo AND situacao IN ('pendente', 'pausada')
                        """)
                .param("arquivo", arquivoId)
                .param("agora", utc(agora))
                .update();
    }

    @Override
    public int ativas(UUID atendimentoId) {
        return jdbc.sql("""
                        SELECT count(*) FROM atendimento.tarefa
                         WHERE atendimento_id = :atendimento AND situacao IN ('pendente', 'executando')
                        """)
                .param("atendimento", atendimentoId)
                .query(Integer.class).single();
    }

    private void terminar(long id, String situacao, String codigo, String mensagem, Instant agora) {
        jdbc.sql("""
                        UPDATE atendimento.tarefa
                           SET situacao = :situacao, em_execucao_ate = NULL, erro_codigo = :codigo, erro_mensagem = :mensagem,
                               atualizada_em = :agora
                         WHERE id = :id AND situacao = 'executando'
                        """)
                .param("id", id)
                .param("situacao", situacao)
                .param("codigo", cortar(codigo, 40))
                .param("mensagem", cortar(mensagem, 500))
                .param("agora", utc(agora))
                .update();
    }

    private static String cortar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    private static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
