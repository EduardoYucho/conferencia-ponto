package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Integração com a API do Gemini ({@code atendimento.gemini} no atendimento.yml).
 *
 * @param urlBase           endereço da API (os testes trocam por um servidor local)
 * @param tempoConexao      espera para abrir a conexão com o Google
 * @param tempoTeste        espera pela resposta do teste da chave
 * @param tempoLimite       espera pela resposta de uma geração (análise de um áudio longo demora)
 * @param tempoEnvio        espera por cada pedaço do envio de um arquivo
 * @param esperaArquivo     quanto esperar o Google deixar um arquivo enviado pronto para uso
 * @param intervaloConsulta de quanto em quanto tempo perguntar se o arquivo enviado ficou pronto
 */
@ConfigurationProperties(prefix = "atendimento.gemini")
public record GeminiProperties(
        @DefaultValue("https://generativelanguage.googleapis.com") URI urlBase,
        @DefaultValue("10s") Duration tempoConexao,
        @DefaultValue("20s") Duration tempoTeste,
        @DefaultValue("10m") Duration tempoLimite,
        @DefaultValue("2m") Duration tempoEnvio,
        @DefaultValue("5m") Duration esperaArquivo,
        @DefaultValue("2s") Duration intervaloConsulta) {
}
