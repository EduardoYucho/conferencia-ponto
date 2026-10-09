package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga o {@link InterceptorDoGerador} às rotas do gerador. O interceptor nasce aqui, num @Bean, e não como
 * componente: assim ele não entra nos testes de controlador do ponto (que carregam todos os interceptores).
 */
@Configuration
public class WebDoGerador {

    static final String ROTAS = "/api/v1/atendimentos/**";

    @Bean
    WebMvcConfigurer rotasDoGerador(AcessoUsuarios acesso, GerenciarAcessosAoGerador acessos) {
        InterceptorDoGerador interceptor = new InterceptorDoGerador(acesso, acessos);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registro) {
                registro.addInterceptor(interceptor)
                        .addPathPatterns(ROTAS)
                        .excludePathPatterns("/api/v1/atendimentos/meu-acesso", "/api/v1/atendimentos/acessos",
                                "/api/v1/atendimentos/acessos/**");
            }
        };
    }
}
