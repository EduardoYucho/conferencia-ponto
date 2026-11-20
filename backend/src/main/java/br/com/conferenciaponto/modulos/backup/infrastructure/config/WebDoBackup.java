package br.com.conferenciaponto.modulos.backup.infrastructure.config;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.backup.infrastructure.web.FiltroDaPortaPublica;
import br.com.conferenciaponto.modulos.backup.infrastructure.web.InterceptorDoBackup;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * O lado web dos backups de clientes:
 * <ul>
 * <li>a porta pública (só em 127.0.0.1), a única que o túnel publica: o {@link FiltroDaPortaPublica} deixa passar por
 * ela apenas {@code /c/...} (a página de coleta e a API dela); o resto do sistema continua só na porta de sempre;</li>
 * <li>os arquivos da página de coleta em {@code /c/_/...} (no jar, em {@code backup/coleta/});</li>
 * <li>o {@link InterceptorDoBackup} nas rotas da tela ({@code /api/v1/backup/**}: exige backup.coletar).</li>
 * </ul>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ConfiguracaoDoBackup.class)
public class WebDoBackup {

    static final String ROTAS = "/api/v1/backup/**";

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> portaPublicaDoBackup(ConfiguracaoDoBackup configuracao) {
        return fabrica -> {
            if (configuracao.portaPublica() <= 0) {
                return;
            }
            Connector conector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            conector.setPort(configuracao.portaPublica());
            conector.setProperty("address", "127.0.0.1");
            conector.setProperty("connectionTimeout", "120000");
            conector.setMaxPostSize(-1);
            fabrica.addAdditionalTomcatConnectors(conector);
        };
    }

    @Bean
    FilterRegistrationBean<FiltroDaPortaPublica> filtroDaPortaPublica(ConfiguracaoDoBackup configuracao) {
        FilterRegistrationBean<FiltroDaPortaPublica> registro =
                new FilterRegistrationBean<>(new FiltroDaPortaPublica(configuracao.portaPublica()));
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registro.addUrlPatterns("/*");
        return registro;
    }

    @Bean
    WebMvcConfigurer rotasDoBackup(AcessoUsuarios acesso) {
        InterceptorDoBackup interceptor = new InterceptorDoBackup(acesso);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registro) {
                registro.addInterceptor(interceptor).addPathPatterns(ROTAS);
            }

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registro) {
                registro.addResourceHandler("/c/_/**")
                        .addResourceLocations("classpath:/backup/coleta/")
                        .setCacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES));
            }
        };
    }
}
