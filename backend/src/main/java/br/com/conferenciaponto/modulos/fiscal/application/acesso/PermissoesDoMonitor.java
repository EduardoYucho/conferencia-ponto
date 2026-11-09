package br.com.conferenciaponto.modulos.fiscal.application.acesso;

import br.com.conferenciaponto.domain.acesso.FonteDePermissoes;
import br.com.conferenciaponto.domain.acesso.Permissao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * As permissões do monitor fiscal, que aparecem na tela de perfis do ponto (grupo "Suporte"). Toda rota do monitor
 * exige {@link #VER}; ler agora (e, nas próximas etapas, cadastrar prefeituras) exige {@link #CONFIGURAR}.
 */
@Component
public class PermissoesDoMonitor implements FonteDePermissoes {

    /** Vê a tela: a situação por estado e documento, os incidentes e a disponibilidade. */
    public static final String VER = "fiscal.ver";
    /** Pede uma leitura fora de hora e, nas próximas etapas, cadastra as prefeituras (inclui ver). */
    public static final String CONFIGURAR = "fiscal.configurar";

    @Override
    public String grupo() {
        return "Suporte";
    }

    @Override
    public int ordem() {
        return 40;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(VER, "Ver o monitor fiscal",
                        "Vê a situação da SEFAZ por estado e documento, os incidentes e a disponibilidade."),
                Permissao.de(CONFIGURAR, "Configurar o monitor",
                        "Pede uma leitura na hora e cadastra as prefeituras acompanhadas.").requer(VER));
    }
}
