package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Um item da linha do tempo, com o identificador usado nas fontes do texto: {@code M<n>} para as mensagens e os
 * eventos da conversa (a ordem no PDF), {@code A<n>} para os anexos da conversa, {@code L<n>} para as ligações,
 * {@code V<n>} para os vídeos e {@code P<n>} para os prints enviados pela pessoa (a ordem de cada origem). Os
 * identificadores não mudam quando a linha é montada de novo com mais arquivos.
 *
 * @param tipo      mensagem, evento ou arquivo
 * @param momento   null quando não se sabe (mensagem sem horário no PDF, arquivo sem data confiável)
 * @param lado      cliente ou atendente (mensagens; nos anexos, o lado de quem enviou)
 * @param remetente quem escreveu a mensagem ou enviou o anexo (null se o PDF não trouxe)
 * @param texto     o texto da mensagem ou do evento (já mascarado); nos arquivos, o que a análise leu
 * @param mensagem  nos anexos: o identificador da mensagem em que foi enviado (ex.: M12)
 * @param situacao  nos arquivos: analisado, sem_analise, falhou, nao_baixado, nao_suportado, tirado, nao_analisavel
 * @param resumo    nos arquivos: uma linha sobre o conteúdo (a tela do print, o tipo do documento), para a legenda
 */
public record ItemDaLinha(String id, TipoDoItem tipo, Instant momento, String lado, String remetente, String texto,
                          UUID arquivoId, String origem, String categoria, String nome, String mensagem,
                          String situacao, String resumo) {

    public enum TipoDoItem {
        MENSAGEM, EVENTO, ARQUIVO;

        public String codigo() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ItemDaLinha {
        texto = texto == null ? "" : texto;
    }

    public boolean arquivo() {
        return tipo == TipoDoItem.ARQUIVO;
    }

    @Override
    public String toString() {
        return "ItemDaLinha[" + id + ", " + tipo + ", momento=" + momento + ", caracteres=" + texto.length() + "]";
    }
}
