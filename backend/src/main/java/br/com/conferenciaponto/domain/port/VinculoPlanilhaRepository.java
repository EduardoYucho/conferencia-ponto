package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.VinculoPlanilha;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: a planilha do Google de cada usuário (no máximo uma por usuário, e uma planilha por pessoa). */
public interface VinculoPlanilhaRepository {

    Optional<VinculoPlanilha> buscar(UUID usuarioId);

    Optional<VinculoPlanilha> buscarPorPlanilha(String planilhaId);

    List<VinculoPlanilha> listar();

    void salvar(VinculoPlanilha vinculo);

    void excluir(UUID usuarioId);
}
