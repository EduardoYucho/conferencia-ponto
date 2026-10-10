package br.com.conferenciaponto.modulos.atendimento.application.espaco;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import org.springframework.stereotype.Service;

/** O espaço em disco dos atendimentos, para a tela Acessos do administrador (sem o conteúdo de ninguém). */
@Service
public class EspacoDosAtendimentos {

    /**
     * @param ocupado  bytes na pasta dos atendimentos (sem a base de conhecimento), ou -1 se não deu para somar
     * @param livre    bytes livres no disco
     * @param pausados atendimentos pausados agora (disco cheio)
     */
    public record EspacoView(long ocupado, long livre, int pausados) {
    }

    private final PastaDosAtendimentos pasta;
    private final Atendimentos atendimentos;

    public EspacoDosAtendimentos(PastaDosAtendimentos pasta, Atendimentos atendimentos) {
        this.pasta = pasta;
        this.atendimentos = atendimentos;
    }

    public EspacoView consultar(Usuario quem) {
        if (!quem.isAdmin()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só o administrador vê o espaço ocupado pelos atendimentos.");
        }
        long livre = pasta.espacoLivre();
        return new EspacoView(pasta.espacoOcupado(), livre == Long.MAX_VALUE ? -1 : livre, atendimentos.pausados());
    }
}
