package br.com.conferenciaponto;

import br.com.conferenciaponto.infrastructure.config.BancoHorasProperties;
import br.com.conferenciaponto.infrastructure.armazenamento.ArmazenamentoProperties;
import br.com.conferenciaponto.infrastructure.config.PontoProperties;
import br.com.conferenciaponto.infrastructure.google.GoogleProperties;
import br.com.conferenciaponto.infrastructure.importacao.ImportacaoPdfProperties;
import br.com.conferenciaponto.infrastructure.log.LogsProperties;
import br.com.conferenciaponto.infrastructure.security.SegurancaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
        PontoProperties.class,
        BancoHorasProperties.class,
        ImportacaoPdfProperties.class,
        ArmazenamentoProperties.class,
        SegurancaProperties.class,
        GoogleProperties.class,
        LogsProperties.class
})
public class ConferenciaPontoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConferenciaPontoApplication.class, args);
    }
}
