package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuração do gerador, registrada aqui (e não na classe da aplicação, que é do ponto). O agendamento (limpeza
 * diária, ping do SSE) é ligado aqui também, para o módulo não depender da configuração do ponto.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({GeminiProperties.class, ChaveMestraProperties.class, DownloadProperties.class,
        DigisacProperties.class})
public class ConfiguracaoDoGerador {
}
