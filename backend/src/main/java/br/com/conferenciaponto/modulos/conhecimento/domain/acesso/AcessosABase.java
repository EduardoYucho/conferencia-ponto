package br.com.conferenciaponto.modulos.conhecimento.domain.acesso;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Os acessos à base (tabela conhecimento.acesso). Quem não tem linha não tem acesso. */
public interface AcessosABase {

    Optional<AcessoABase> buscar(UUID usuarioId);

    List<AcessoABase> listar();

    /** Cria ou substitui o acesso da pessoa. */
    void salvar(AcessoABase acesso);
}
