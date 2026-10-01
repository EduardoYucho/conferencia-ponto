package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.HorarioTrabalho;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: horários de trabalho (com vigência) de cada usuário. */
public interface HorarioTrabalhoRepository {

    void salvar(HorarioTrabalho horario);

    Optional<HorarioTrabalho> buscarPorId(UUID id);

    /** Do mais antigo para o mais recente (por vigência). */
    List<HorarioTrabalho> listarPorUsuario(UUID usuarioId);

    void excluir(UUID id);
}
