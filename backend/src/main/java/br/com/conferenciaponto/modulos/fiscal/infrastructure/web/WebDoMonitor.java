package br.com.conferenciaponto.modulos.fiscal.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga o {@link InterceptorDoMonitor} às rotas do monitor. O interceptor nasce aqui, num @Bean, e não como
 * componente: assim ele não entra nos testes de controlador do ponto (que carregam todos os interceptores).
 */
@Configuration
public class WebDoMonitor {

    static final String ROTAS = "/api/v1/fiscal/**";

    @Bean
    WebMvcConfigurer rotasDoMonitor(AcessoUsuarios acesso) {
        InterceptorDoMonitor interceptor = new InterceptorDoMonitor(acesso);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registro) {
                registro.addInterceptor(interceptor)
                        .addPathPatterns(ROTAS)
                        .excludePathPatterns("/api/v1/fiscal/meu-acesso");
            }
        };
    }
}
