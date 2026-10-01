package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class LancamentoBancoRepositoryEmMemoria implements LancamentoBancoRepository {

    private final Map<UUID, LancamentoBanco> lancamentos = new LinkedHashMap<>();

    @Override
    public LancamentoBanco salvar(LancamentoBanco lancamento) {
        lancamentos.put(lancamento.id(), lancamento);
        return lancamento;
    }

    @Override
    public Optional<LancamentoBanco> buscarPorId(UUID id) {
        return Optional.ofNullable(lancamentos.get(id));
    }

    @Override
    public void excluir(UUID id) {
        lancamentos.remove(id);
    }

    @Override
    public List<LancamentoBanco> listarNoPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return lancamentos.values().stream()
                .filter(l -> l.usuarioId().equals(usuarioId))
                .filter(l -> !l.data().isBefore(inicio) && !l.data().isAfter(fim))
                .sorted(Comparator.comparing(LancamentoBanco::data).thenComparing(LancamentoBanco::criadoEm))
                .toList();
    }
}
