package br.com.conferenciaponto.infrastructure.config;

import br.com.conferenciaponto.application.usecase.GerenciarUsuariosUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Na subida (depois de criar os usuários iniciais e antes do recálculo): todo usuário com dados de ponto tem o
 * horário gravado (o padrão da configuração, valendo para todo o histórico) e um ciclo do banco aberto.
 */
@Component
@Order(5)
class InicializadorTitulares implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorTitulares.class);

    private final GerenciarUsuariosUseCase usuarios;

    InicializadorTitulares(GerenciarUsuariosUseCase usuarios) {
        this.usuarios = usuarios;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            usuarios.prepararTitulares();
        } catch (RuntimeException e) {
            // não impede a subida: quem ficou sem horário usa o padrão da configuração até a próxima tentativa
            log.warn("Não foi possível preparar o horário/ciclo dos usuários: {}", e.getMessage());
        }
    }
}
