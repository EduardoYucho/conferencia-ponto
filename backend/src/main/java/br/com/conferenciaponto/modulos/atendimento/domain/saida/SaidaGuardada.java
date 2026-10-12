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
 * @param confirmadaEm quando o autor confirmou o texto e o enviou para a base (null = não confirmada)
 */
public record SaidaGuardada(UUID id, UUID atendimentoId, TipoDeSaida tipo, int versao, SituacaoDaSaida situacao,
                            String erroMensagem, String estrutura, String html, String htmlEditado, String assunto,
                            String modelo, Integer versaoPrompt, Integer tokensEntrada, Integer tokensSaida,
                            Instant criadaEm, Instant confirmadaEm) {

    public SaidaGuardada(UUID id, UUID atendimentoId, TipoDeSaida tipo, int versao, SituacaoDaSaida situacao,
                         String erroMensagem, String estrutura, String html, String htmlEditado, String assunto,
                         String modelo, Integer versaoPrompt, Integer tokensEntrada, Integer tokensSaida,
                         Instant criadaEm) {
        this(id, atendimentoId, tipo, versao, situacao, erroMensagem, estrutura, html, htmlEditado, assunto, modelo,
                versaoPrompt, tokensEntrada, tokensSaida, criadaEm, null);
    }

    /** O HTML que vale agora (o editado, se houver). */
    public String htmlAtual() {
        return htmlEditado != null ? htmlEditado : html;
    }

    @Override
    public String toString() {
        return "SaidaGuardada[" + id + ", " + tipo + " v" + versao + ", " + situacao + "]";
    }
}
