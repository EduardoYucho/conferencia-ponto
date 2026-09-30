package br.com.conferenciaponto.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serve o front-end (Vue) empacotado no próprio jar, em {@code classpath:/static/} — o build com o
 * perfil Maven {@code app} copia o {@code frontend/dist} para lá. Assim a aplicação inteira sobe com um
 * único {@code java -jar}, na mesma origem da API (sem CORS nem proxy).
 *
 * <p>O roteador do Vue usa o modo <i>history</i> ({@code /auditoria}, {@code /conciliacao}...): qualquer
 * caminho sem extensão que não seja arquivo nem API devolve o {@code index.html}, e o Vue decide a tela.
 * Arquivo inexistente ({@code /assets/x.js}) e rota inexistente da API continuam em 404. Sem o front-end
 * no jar (desenvolvimento, com o Vite na 5173), tudo fora da API é 404.
 */
@Configuration
public class FrontendConfig implements WebMvcConfigurer {

    static final String LOCAL = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(LOCAL)
                .resourceChain(true)
                .addResolver(new RotasDoFrontend());
    }

    /** Devolve o arquivo pedido ou, para rotas do Vue, o index.html. */
    static class RotasDoFrontend extends PathResourceResolver {

        @Override
        protected Resource getResource(String caminho, Resource local) throws IOException {
            if (!caminho.isEmpty() && !caminho.endsWith("/")) {
                Resource arquivo = super.getResource(caminho, local);
                if (arquivo != null) {
                    return arquivo;
                }
            }
            return ehRotaDoFrontend(caminho) ? super.getResource("index.html", local) : null;
        }

        static boolean ehRotaDoFrontend(String caminho) {
            if (caminho.equals("api") || caminho.startsWith("api/")) {
                return false;
            }
            String ultimo = caminho.substring(caminho.lastIndexOf('/') + 1);
            return !ultimo.contains(".");
        }
    }
}
