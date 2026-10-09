package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuração do gerador, registrada aqui (e não na classe da aplicação, que é do ponto). */
@Configuration
@EnableConfigurationProperties({GeminiProperties.class, ChaveMestraProperties.class})
public class ConfiguracaoDoGerador {
}
