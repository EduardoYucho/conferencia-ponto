package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** As saídas (textos gerados) e as imagens delas no banco. Quem chama já conferiu o dono do atendimento. */
public interface Saidas {

    Optional<SaidaGuardada> buscar(UUID saidaId);

    /** A versão mais recente de cada tipo, na ordem dos tipos. */
    List<SaidaGuardada> ultimas(UUID atendimentoId);

    /** A mais recente do tipo. */
    Optional<SaidaGuardada> ultima(UUID atendimentoId, TipoDeSaida tipo);

    /**
     * Cria a versão seguinte do tipo (a 1, se é a primeira), pendente, se o tipo ainda não tem nenhuma ou se a mais
     * recente está numa destas situações (conferido e criado sob uma trava: dois pedidos ao mesmo tempo criam uma só).
     *
     * @return o id da nova, ou vazio se a mais recente estava em outra situação
     */
    Optional<UUID> criar(UUID atendimentoId, TipoDeSaida tipo, Set<SituacaoDaSaida> seAUltimaFor, Instant agora);

    /** As que esperam a redação (pendentes ou redigindo), na ordem dos tipos. */
    List<UUID> esperando(UUID atendimentoId);

    /**
     * Muda a situação só se ela ainda é uma destas (numa única gravação).
     *
     * @param erroMensagem a mensagem para a pessoa (null limpa)
     * @return false se a situação já era outra
     */
    boolean mudarSituacaoSe(UUID saidaId, Set<SituacaoDaSaida> de, SituacaoDaSaida nova, String erroMensagem);

    /** As que esperam a redação falham com a mensagem (a linha do tempo não pôde ser montada). */
    int falharEsperando(UUID atendimentoId, String erroMensagem);

    /**
     * O texto redigido: grava a estrutura, o HTML e o assunto, troca as imagens da saída pelas novas e deixa a saída
     * pronta (sem edição), numa transação.
     *
     * @return false se a saída já não estava esperando a redação (nada muda)
     */
    boolean salvarRedacao(UUID saidaId, String estrutura, String html, String assunto, String modelo, int versaoPrompt,
                       Integer tokensEntrada, Integer tokensSaida, List<NovaImagem> imagens);

    List<ImagemDaSaida> imagens(UUID saidaId);

    /**
     * O HTML editado (null volta ao montado) e a numeração e as legendas das imagens que valem para ele, numa
     * transação.
     *
     * @param ordem as imagens na nova ordem: as incluídas primeiro, na ordem do texto
     */
    void salvarTexto(UUID saidaId, String htmlEditado, List<ImagemNumerada> ordem);

    void salvarAssunto(UUID saidaId, String assunto);

    /** A Tela/Funcionalidade do cabeçalho dos chamados (fica na estrutura do texto). */
    void salvarTela(UUID saidaId, String telaFuncionalidade);

    /** Tokens gastos nas redações do atendimento (todas as versões): [entrada, saída]. */
    long[] tokens(UUID atendimentoId);

    /** O número, a inclusão e a legenda de uma imagem depois de uma mudança. */
    record ImagemNumerada(UUID id, int numero, boolean incluida, String legenda) {
    }
}
