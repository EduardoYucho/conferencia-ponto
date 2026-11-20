package br.com.receptorbackup.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Duas portas, as duas só em 127.0.0.1: a da tela do Receptor (server.port) e a pública, que é a única que o túnel
 * publica na internet. O {@link FiltroDePortas} garante que a pública só atende /c/... (a página de coleta e a API dela).
 */
@Configuration
public class ConfiguracaoWeb {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> portaPublica(Propriedades propriedades) {
        return fabrica -> {
            Connector conector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            conector.setPort(propriedades.portaPublica());
            conector.setProperty("address", "127.0.0.1");
            conector.setProperty("connectionTimeout", "120000");
            conector.setMaxPostSize(-1);
            fabrica.addAdditionalTomcatConnectors(conector);
        };
    }

    @Bean
    FilterRegistrationBean<FiltroDePortas> filtroDePortas(Propriedades propriedades) {
        FilterRegistrationBean<FiltroDePortas> registro = new FilterRegistrationBean<>(new FiltroDePortas(propriedades));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registro.addUrlPatterns("/*");
        return registro;
    }
}
