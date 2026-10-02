package br.com.conferenciaponto.application.planilha;

/**
 * A planilha remota (Google Sheets) recusou a operação ou não respondeu.
 *
 * <p>{@code transitoria}: vale tentar de novo sozinho (rede fora, limite de requisições, erro do serviço);
 * as demais dependem de alguém agir (compartilhar a planilha, ativar a API, trocar a chave).
 */
public class PlanilhaRemotaException extends RuntimeException {

    private final String codigo;
    private final boolean transitoria;

    public PlanilhaRemotaException(String codigo, String mensagem, boolean transitoria) {
        this(codigo, mensagem, transitoria, null);
    }

    public PlanilhaRemotaException(String codigo, String mensagem, boolean transitoria, Throwable causa) {
        super(mensagem, causa);
        this.codigo = codigo;
        this.transitoria = transitoria;
    }

    public String getCodigo() {
        return codigo;
    }

    public boolean isTransitoria() {
        return transitoria;
    }
}
