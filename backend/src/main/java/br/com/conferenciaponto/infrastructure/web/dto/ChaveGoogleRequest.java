package br.com.conferenciaponto.infrastructure.web.dto;

/** @param chave conteúdo do arquivo JSON da chave da conta de serviço */
public record ChaveGoogleRequest(String chave) {

    /** Nunca registra a chave em log. */
    @Override
    public String toString() {
        return "ChaveGoogleRequest[chave=***]";
    }
}
