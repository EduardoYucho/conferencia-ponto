package br.com.conferenciaponto.modulos.conhecimento.application.acesso;

import br.com.conferenciaponto.domain.acesso.FonteDePermissoes;
import br.com.conferenciaponto.domain.acesso.Permissao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * As permissões da base de conhecimento, que aparecem na tela de perfis do ponto. Toda rota da base exige
 * {@link #PESQUISAR} (ver InterceptorDaBase); o que só a curadoria faz é conferido nos casos de uso.
 */
@Component
public class PermissoesDaBase implements FonteDePermissoes {

    /** Pesquisa, abre os registros e envia textos confirmados para a base. */
    public static final String PESQUISAR = "base.pesquisar";
    /** Edita qualquer registro e muda a situação no desenvolvimento (inclui pesquisar). */
    public static final String CURAR = "base.curar";

    @Override
    public String grupo() {
        return "Base de conhecimento";
    }

    @Override
    public int ordem() {
        return 30;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(PESQUISAR, "Pesquisar na base",
                        "Pesquisa, abre os registros e envia os textos confirmados para a base."),
                Permissao.de(CURAR, "Curar a base",
                        "Edita qualquer registro e muda a situação no desenvolvimento.").requer(PESQUISAR));
    }
}
