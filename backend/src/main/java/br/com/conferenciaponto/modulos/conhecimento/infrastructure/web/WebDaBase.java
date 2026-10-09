package br.com.conferenciaponto.modulos.conhecimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga o {@link InterceptorDaBase} às rotas da base. O interceptor nasce aqui, num @Bean, e não como
 * componente: assim ele não entra nos testes de controlador do ponto (que carregam todos os interceptores).
 */
@Configuration
public class WebDaBase {

    static final String ROTAS = "/api/v1/conhecimento/**";

    @Bean
    WebMvcConfigurer rotasDaBase(AcessoUsuarios acesso, GerenciarAcessosABase acessos) {
        InterceptorDaBase interceptor = new InterceptorDaBase(acesso, acessos);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registro) {
                registro.addInterceptor(interceptor)
                        .addPathPatterns(ROTAS)
                        .excludePathPatterns("/api/v1/conhecimento/meu-acesso", "/api/v1/conhecimento/acessos",
                                "/api/v1/conhecimento/acessos/**");
            }
        };
    }
}
