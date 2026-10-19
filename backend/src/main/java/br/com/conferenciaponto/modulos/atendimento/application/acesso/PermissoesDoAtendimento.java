package br.com.conferenciaponto.modulos.atendimento.application.acesso;

import br.com.conferenciaponto.domain.acesso.FonteDePermissoes;
import br.com.conferenciaponto.domain.acesso.Permissao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * As permissões do gerador de atendimentos, que aparecem na tela de perfis do ponto. Toda rota do gerador exige
 * {@link #USAR} (ver InterceptorDoGerador), menos o "meu acesso" e o espaço em disco.
 */
@Component
public class PermissoesDoAtendimento implements FonteDePermissoes {

    /** Abre atendimentos, processa os arquivos e gera os textos (cada pessoa vê os seus). */
    public static final String USAR = "atendimentos.usar";
    /** Vê quanto os atendimentos ocupam no disco e quanto sobra. */
    public static final String ESPACO = "atendimentos.espaco";

    @Override
    public String grupo() {
        return "Atendimentos";
    }

    @Override
    public int ordem() {
        return 20;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(USAR, "Usar os atendimentos",
                        "Abre atendimentos, processa os arquivos e gera os textos (cada pessoa vê os seus)."),
                Permissao.de(ESPACO, "Ver o espaço em disco dos atendimentos",
                        "Quanto os atendimentos ocupam no disco e quanto sobra."));
    }
}
