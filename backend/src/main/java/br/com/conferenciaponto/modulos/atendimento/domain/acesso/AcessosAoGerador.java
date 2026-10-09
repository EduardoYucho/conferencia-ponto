package br.com.conferenciaponto.modulos.atendimento.domain.acesso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Os acessos ao gerador (tabela atendimento.acesso). Quem não tem linha não tem acesso. */
public interface AcessosAoGerador {

    Optional<AcessoAoGerador> buscar(UUID usuarioId);

    List<AcessoAoGerador> listar();

    /** Cria ou substitui o acesso da pessoa. */
    void salvar(AcessoAoGerador acesso);
}
