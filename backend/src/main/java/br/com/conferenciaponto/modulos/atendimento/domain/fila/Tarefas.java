package br.com.conferenciaponto.modulos.atendimento.domain.fila;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A fila no banco (atendimento.tarefa). */
public interface Tarefas {

    /**
     * Cria a tarefa, a menos que já exista uma pendente, em execução ou pausada do mesmo tipo para o mesmo arquivo
     * (ou atendimento/saída). Fica pronta para rodar já ({@code agora}). @return true se criou
     */
    boolean criar(TipoDeTarefa tipo, UUID atendimentoId, UUID arquivoId, UUID saidaId, int maxTentativas, Instant agora);

    /**
     * Pega até {@code limite} tarefas prontas ({@code FOR UPDATE SKIP LOCKED}, numa transação), respeitando o limite
     * por pessoa e por tipo (contando as que já estão em execução), e as marca como em execução até
     * {@code agora + prazo}, com uma tentativa a mais.
     */
    List<Tarefa> pegar(int limite, int porUsuario, Map<TipoDeTarefa, Integer> porTipo, Instant agora, Duration prazo);

    /** Renova o prazo das tarefas que este processo ainda está executando. */
    void renovar(Collection<Long> ids, Instant ate);

    /**
     * Devolve à fila as tarefas em execução cujo prazo venceu (quem executava parou: o serviço reiniciou).
     * @return quantas voltaram
     */
    int devolverVencidas(Instant agora);

    /** Na subida: nada está em execução de verdade, então toda tarefa "executando" volta para a fila. */
    int devolverTodasEmExecucao();

    void concluir(long id, Instant agora);

    /** Falha temporária: volta a pendente para depois de {@code executarApos}. */
    void adiar(long id, Instant executarApos, String codigo, String mensagem, Instant agora);

    void falhar(long id, String codigo, String mensagem, Instant agora);

    /** A tarefa em execução fica pausada (volta quando o atendimento for retomado, sem gastar tentativa). */
    void pausar(long id, String codigo, String mensagem, Instant agora);

    /** Pendentes e pausadas do atendimento viram canceladas. @return quantas */
    int cancelarDoAtendimento(UUID atendimentoId, Instant agora);

    /** Pendentes do atendimento viram pausadas. */
    int pausarDoAtendimento(UUID atendimentoId, Instant agora);

    /** Pausadas do atendimento voltam a pendentes, para já. */
    int retomarDoAtendimento(UUID atendimentoId, Instant agora);

    /** Pendentes e pausadas de um arquivo viram canceladas (o arquivo foi enviado à mão ou tirado). */
    int cancelarDoArquivo(UUID arquivoId, Instant agora);

    /** Tarefas pendentes ou em execução do atendimento. */
    int ativas(UUID atendimentoId);
}
