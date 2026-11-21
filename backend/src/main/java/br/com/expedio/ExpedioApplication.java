package br.com.expedio;

import br.com.expedio.infrastructure.config.BancoHorasProperties;
import br.com.expedio.infrastructure.armazenamento.ArmazenamentoProperties;
import br.com.expedio.infrastructure.config.PontoProperties;
import br.com.expedio.infrastructure.google.GoogleProperties;
import br.com.expedio.infrastructure.importacao.ImportacaoPdfProperties;
import br.com.expedio.infrastructure.log.LogsProperties;
import br.com.expedio.infrastructure.security.SegurancaProperties;
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
public class ExpedioApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExpedioApplication.class, args);
    }
}
