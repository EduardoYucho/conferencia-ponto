package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;

import java.time.Instant;
import java.util.Map;

/**
 * O que o PDF trouxe, para a tela mostrar logo depois do envio.
 *
 * @param arquivo             nome do arquivo enviado (só para a tela; não é guardado)
 * @param anexosPorCategoria  ex.: {imagem: 2, audio: 3}
 * @param linksVencidos       os links dos anexos já venceram (o PDF foi exportado há mais de 24 h)
 * @param linhasSemCabecalho  linhas de mensagem sem remetente e horário (muitas indicam que o layout mudou)
 */
public record LeituraView(String arquivo, String chamado, String contato, Instant inicio, Instant fim, String assunto,
                          int mensagens, int eventos, int anexos, Map<String, Integer> anexosPorCategoria,
                          Instant linksValidosAte, boolean linksVencidos, Omitidos omitidos, int linhasSemCabecalho) {
}
