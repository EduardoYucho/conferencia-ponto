package br.com.conferenciaponto.infrastructure.web.acesso;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injeta o {@link Titular} nos controllers: o usuário do token e, nos GET, o {@code ?usuario=login} pedido
 * pelo administrador ou pela coordenação.
 */
@Component
public class TitularArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String PARAMETRO = "usuario";

    private final AcessoUsuarios acesso;

    public TitularArgumentResolver(AcessoUsuarios acesso) {
        this.acesso = acesso;
    }

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return Titular.class.equals(parametro.getParameterType());
    }

    @Override
    public Titular resolveArgument(MethodParameter parametro, ModelAndViewContainer mav, NativeWebRequest requisicao,
                                   WebDataBinderFactory binder) {
        HttpServletRequest http = requisicao.getNativeRequest(HttpServletRequest.class);
        boolean leitura = http == null || HttpMethod.GET.matches(http.getMethod())
                || HttpMethod.HEAD.matches(http.getMethod());
        return acesso.titular(acesso.logado(), requisicao.getParameter(PARAMETRO), !leitura);
    }
}
