package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

/** O PDF enviado não pôde ser lido como uma conversa do Digisac (HTTP 422, com a explicação para a pessoa). */
public class LeituraDoPdfException extends RegraNegocioException {

    public LeituraDoPdfException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }

    public static LeituraDoPdfException invalido() {
        return new LeituraDoPdfException("PDF_INVALIDO",
                "O arquivo não é um PDF válido ou está corrompido. Exporte a conversa de novo no Digisac e envie o PDF.");
    }

    public static LeituraDoPdfException protegido() {
        return new LeituraDoPdfException("PDF_PROTEGIDO",
                "O PDF está protegido por senha. Envie o PDF exportado pelo Digisac, sem senha.");
    }

    public static LeituraDoPdfException naoEDigisac() {
        return new LeituraDoPdfException("PDF_NAO_E_DIGISAC",
                "Este PDF não parece uma conversa exportada do Digisac. No Digisac, abra o chamado e use a opção de "
                        + "exportar a conversa em PDF.");
    }

    public static LeituraDoPdfException layoutDesconhecido() {
        return new LeituraDoPdfException("PDF_LAYOUT_DESCONHECIDO",
                "O formato do PDF do Digisac mudou e o sistema não conseguiu ler a conversa. Avise o administrador e "
                        + "informe o protocolo.");
    }

    public static LeituraDoPdfException semMensagens() {
        return new LeituraDoPdfException("PDF_SEM_MENSAGENS",
                "Este PDF do Digisac não tem mensagens. Confira se exportou o chamado certo.");
    }

    public static LeituraDoPdfException grandeDemais(String limite) {
        return new LeituraDoPdfException("PDF_GRANDE_DEMAIS",
                "O PDF passa do tamanho máximo aceito (" + limite + "). Exporte só o chamado do atendimento.");
    }
}
