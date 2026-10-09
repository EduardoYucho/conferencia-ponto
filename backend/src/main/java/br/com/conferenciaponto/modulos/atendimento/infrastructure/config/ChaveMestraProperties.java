package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Onde fica a chave mestra que cifra as chaves do Gemini ({@code atendimento.chave-mestra.arquivo}). Fica fora do
 * banco: um backup do banco sozinho não abre as chaves. O "ponto backup" copia este arquivo junto.
 */
@ConfigurationProperties(prefix = "atendimento.chave-mestra")
public record ChaveMestraProperties(Path arquivo) {

    public ChaveMestraProperties {
        if (arquivo == null) {
            throw new IllegalStateException("Defina atendimento.chave-mestra.arquivo (veja o atendimento.yml).");
        }
    }
}
