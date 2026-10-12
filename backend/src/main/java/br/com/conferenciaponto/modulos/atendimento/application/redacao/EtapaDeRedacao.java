package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A redação das saídas (resumo e, nas etapas seguintes, os chamados para o desenvolvimento), vista pelo
 * processamento: a redação começa quando não há mais download nem análise na fila (a linha do tempo é montada e,
 * depois dela, cada saída pedida é redigida).
 */
public interface EtapaDeRedacao {

    /**
     * Cria as saídas pedidas que ainda não existem (ou cuja última versão falhou). As que já estão prontas ficam
     * (para outra versão, "gerar de novo").
     *
     * @return quantas foram criadas
     */
    int pedir(UUID atendimentoId, Set<TipoDeSaida> tipos, Instant agora);

    /**
     * Uma versão nova do tipo, para redigir de novo (as análises guardadas são aproveitadas).
     *
     * @return false se uma versão do tipo já está esperando a redação
     */
    boolean pedirNova(UUID atendimentoId, TipoDeSaida tipo, Instant agora);

    /** Há saída esperando a redação (pendente ou redigindo). */
    boolean esperando(UUID atendimentoId);

    /**
     * Chamado quando não há mais nada na fila do atendimento: se há saída esperando, a montagem da linha do tempo
     * entra na fila (uma só, mesmo com dois pedidos ao mesmo tempo).
     *
     * @return true se há saída esperando (e a linha do tempo está na fila)
     */
    boolean seguir(UUID atendimentoId, Instant agora);

    /**
     * A linha do tempo ficou pronta: a redação de cada saída esperando entra na fila (chamado com a tarefa da linha
     * do tempo ainda ativa, para o atendimento não parecer pronto no meio do caminho).
     *
     * @return quantas redações entraram na fila
     */
    int redigirAsEsperando(UUID atendimentoId, Instant agora);

    /** O fim de uma tarefa da redação (linha do tempo ou redigir) que não deu certo fica registrado na saída. */
    void registrar(Tarefa tarefa, ResultadoDaTarefa resultado);

    /** A versão mais recente de algum tipo falhou de vez. */
    boolean comFalha(UUID atendimentoId);

    /** A versão mais recente de cada tipo. */
    List<EstadoDaSaida> estados(UUID atendimentoId);

    /** Tokens gastos nas redações do atendimento (todas as versões): [entrada, saída]. */
    long[] tokens(UUID atendimentoId);

    /** Sem redação (os testes das etapas anteriores). */
    EtapaDeRedacao NENHUMA = new EtapaDeRedacao() {
        @Override
        public int pedir(UUID atendimentoId, Set<TipoDeSaida> tipos, Instant agora) {
            return 0;
        }

        @Override
        public boolean pedirNova(UUID atendimentoId, TipoDeSaida tipo, Instant agora) {
            return false;
        }

        @Override
        public boolean esperando(UUID atendimentoId) {
            return false;
        }

        @Override
        public boolean seguir(UUID atendimentoId, Instant agora) {
            return false;
        }

        @Override
        public int redigirAsEsperando(UUID atendimentoId, Instant agora) {
            return 0;
        }

        @Override
        public void registrar(Tarefa tarefa, ResultadoDaTarefa resultado) {
        }

        @Override
        public boolean comFalha(UUID atendimentoId) {
            return false;
        }

        @Override
        public List<EstadoDaSaida> estados(UUID atendimentoId) {
            return List.of();
        }

        @Override
        public long[] tokens(UUID atendimentoId) {
            return new long[]{0, 0};
        }
    };
}
