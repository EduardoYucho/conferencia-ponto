package br.com.conferenciaponto.infrastructure.web.dto;

/**
 * Pasta gravada e o que o servidor enxerga dela agora.
 *
 * @param acessivel o computador onde o sistema roda consegue abrir a pasta
 * @param aviso     orientação quando não consegue (ou quando a conferência demorou demais)
 */
public record PastaResponse(String pasta, boolean acessivel, String aviso, UsuarioResponse usuario) {
}
