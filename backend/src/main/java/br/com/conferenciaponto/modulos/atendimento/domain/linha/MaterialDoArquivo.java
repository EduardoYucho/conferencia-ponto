package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo do atendimento como entra na linha do tempo: onde e quando apareceu, e o texto do que a análise leu
 * (ou por que não há análise).
 *
 * @param mensagemOrdem nos anexos da conversa: a ordem da mensagem em que foi enviado (null se não se sabe)
 * @param momento       nos anexos, o da mensagem; nos arquivos da pessoa, a data de modificação do arquivo
 * @param conteudo      o que a análise leu, já em texto (ou o motivo de não haver análise)
 * @param situacao      analisado, sem_analise, falhou, nao_baixado, nao_suportado, tirado ou nao_analisavel
 * @param resumo        uma linha sobre o conteúdo (a tela do print, o tipo do documento), ou null
 */
public record MaterialDoArquivo(UUID id, String origem, int ordem, String nome, String categoria, Integer mensagemOrdem,
                                Instant momento, String conteudo, String situacao, String resumo) {

    public static final String ANEXO = "anexo_conversa";
    public static final String LIGACAO = "ligacao";
    public static final String VIDEO = "video";
    public static final String PRINT = "print_extra";

    /** O identificador nas fontes: A (anexo), L (ligação), V (vídeo) ou P (print), com a ordem da origem. */
    public String identificador() {
        String prefixo = switch (origem) {
            case ANEXO -> "A";
            case LIGACAO -> "L";
            case VIDEO -> "V";
            case PRINT -> "P";
            default -> "X";
        };
        return prefixo + ordem;
    }

    public boolean daConversa() {
        return ANEXO.equals(origem);
    }
}
