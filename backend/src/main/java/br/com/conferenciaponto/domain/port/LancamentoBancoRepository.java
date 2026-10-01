package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.LancamentoBanco;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: lançamentos avulsos no banco de horas. */
public interface LancamentoBancoRepository {

    LancamentoBanco salvar(LancamentoBanco lancamento);

    Optional<LancamentoBanco> buscarPorId(UUID id);

    void excluir(UUID id);

    /** Lançamentos com data no período (inclusive), por data e ordem de criação. */
    List<LancamentoBanco> listarNoPeriodo(LocalDate inicio, LocalDate fim);
}
