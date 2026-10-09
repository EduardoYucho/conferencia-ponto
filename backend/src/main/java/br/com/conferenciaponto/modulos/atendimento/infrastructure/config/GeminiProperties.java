package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Integração com a API do Gemini ({@code atendimento.gemini} no atendimento.yml).
 *
 * @param urlBase      endereço da API (os testes trocam por um servidor local)
 * @param tempoConexao espera para abrir a conexão com o Google
 * @param tempoTeste   espera pela resposta do teste da chave
 */
@ConfigurationProperties(prefix = "atendimento.gemini")
public record GeminiProperties(
        @DefaultValue("https://generativelanguage.googleapis.com") URI urlBase,
        @DefaultValue("10s") Duration tempoConexao,
        @DefaultValue("20s") Duration tempoTeste) {
}
