package br.com.conferenciaponto.infrastructure.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Integração com o Google Sheets (prefixo {@code ponto.google}).
 *
 * @param credencial  arquivo com a chave da conta de serviço (padrão: ~/.conferencia-ponto/google/conta-de-servico.json);
 *                    o administrador envia a chave pela tela e o sistema grava aqui
 * @param urlToken    onde a chave é trocada por um token de acesso
 * @param urlApi      endereço da API do Google Sheets
 * @param espera      quanto esperar depois de uma mudança antes de gravar a planilha (junta mudanças seguidas)
 * @param tempoLimite tempo máximo de cada chamada ao Google
 */
@ConfigurationProperties(prefix = "ponto.google")
public record GoogleProperties(
        String credencial,
        @DefaultValue("https://oauth2.googleapis.com/token") String urlToken,
        @DefaultValue("https://sheets.googleapis.com") String urlApi,
        @DefaultValue("4s") Duration espera,
        @DefaultValue("30s") Duration tempoLimite) {

    public Path arquivoCredencial() {
        if (credencial == null || credencial.isBlank()) {
            return Path.of(System.getProperty("user.home"), ".conferencia-ponto", "google", "conta-de-servico.json");
        }
        return Path.of(credencial).toAbsolutePath().normalize();
    }
}
