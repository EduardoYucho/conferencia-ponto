package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma saída como está no banco.
 *
 * @param versao       1, 2, 3... ("gerar de novo" cria a seguinte; as anteriores ficam)
 * @param erroMensagem por que falhou, ou a última tentativa que não deu certo (para a pessoa ler)
 * @param estrutura    o texto conferido em JSON (blocos com as fontes, campos da base e avisos)
 * @param html         o HTML montado pelo servidor
 * @param htmlEditado  o HTML depois de a pessoa mexer (texto ou imagens), já limpo; null = vale o montado
 */
public record SaidaGuardada(UUID id, UUID atendimentoId, TipoDeSaida tipo, int versao, SituacaoDaSaida situacao,
                            String erroMensagem, String estrutura, String html, String htmlEditado, String assunto,
                            String modelo, Integer versaoPrompt, Integer tokensEntrada, Integer tokensSaida,
                            Instant criadaEm) {

    /** O HTML que vale agora (o editado, se houver). */
    public String htmlAtual() {
        return htmlEditado != null ? htmlEditado : html;
    }

    @Override
    public String toString() {
        return "SaidaGuardada[" + id + ", " + tipo + " v" + versao + ", " + situacao + "]";
    }
}
