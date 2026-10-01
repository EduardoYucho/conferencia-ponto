package br.com.conferenciaponto.infrastructure.config;

import br.com.conferenciaponto.infrastructure.web.acesso.TitularArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PontoProperties properties;
    private final TitularArgumentResolver titular;

    public WebConfig(PontoProperties properties, TitularArgumentResolver titular) {
        this.properties = properties;
        this.titular = titular;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(titular);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.corsOrigensPermitidas().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .exposedHeaders("Content-Disposition");
    }
}
