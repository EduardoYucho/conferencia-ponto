package br.com.conferenciaponto.infrastructure.config;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import br.com.conferenciaponto.infrastructure.web.acesso.InterceptorSenhaProvisoria;
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
    private final InterceptorSenhaProvisoria senhaProvisoria;

    public WebConfig(PontoProperties properties, TitularArgumentResolver titular,
                     InterceptorSenhaProvisoria senhaProvisoria) {
        this.properties = properties;
        this.titular = titular;
        this.senhaProvisoria = senhaProvisoria;
    }

    /** Senha provisória: só a sessão e a troca de senha ficam liberadas. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(senhaProvisoria)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/erros-de-tela");
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
                .exposedHeaders("Content-Disposition", "X-Protocolo");
    }
}
