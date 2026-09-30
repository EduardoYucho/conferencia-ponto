package br.com.conferenciaponto.infrastructure.armazenamento;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Configuração do armazenamento de comprovantes (prefixo {@code ponto.armazenamento}).
 *
 * @param diretorio raiz dos PDFs arquivados (padrão: ~/.conferencia-ponto/comprovantes)
 * @param urlBase   prefixo da URI de download devolvida pela API
 */
@ConfigurationProperties(prefix = "ponto.armazenamento")
public record ArmazenamentoProperties(
        String diretorio,
        @DefaultValue("/api/comprovantes") String urlBase) {

    public Path diretorioRaiz() {
        if (diretorio == null || diretorio.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".conferencia-ponto", "comprovantes");
        }
        return Path.of(diretorio).toAbsolutePath().normalize();
    }
}
