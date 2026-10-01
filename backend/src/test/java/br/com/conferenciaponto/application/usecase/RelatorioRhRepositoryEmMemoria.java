package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

class RelatorioRhRepositoryEmMemoria implements RelatorioRhRepository {

    private final Map<UUID, RelatorioRh> relatorios = new LinkedHashMap<>();
    private final Map<UUID, List<DiaRelatorioRh>> dias = new LinkedHashMap<>();

    @Override
    public void salvar(RelatorioRh relatorio, List<DiaRelatorioRh> d) {
        relatorios.put(relatorio.id(), relatorio);
        dias.put(relatorio.id(), List.copyOf(d));
    }

    @Override
    public void atualizar(RelatorioRh relatorio) {
        relatorios.put(relatorio.id(), relatorio);
    }

    @Override
    public Optional<RelatorioRh> buscarPorId(UUID id) {
        return Optional.ofNullable(relatorios.get(id));
    }

    @Override
    public Optional<RelatorioRh> buscarPorHash(UUID usuarioId, String hash) {
        return relatorios.values().stream()
                .filter(r -> r.usuarioId().equals(usuarioId) && r.hashSha256().equals(hash)).findFirst();
    }

    @Override
    public List<RelatorioRh> listar(UUID usuarioId) {
        return relatorios.values().stream().filter(r -> r.usuarioId().equals(usuarioId)).sorted(Comparator.comparing(RelatorioRh::emitidoEm).reversed()).toList();
    }

    @Override
    public void excluir(UUID id) {
        relatorios.remove(id);
        dias.remove(id);
    }

    @Override
    public List<DiaRelatorioRh> dias(UUID relatorioId) {
        return dias.getOrDefault(relatorioId, List.of());
    }

    @Override
    public List<DiaVigente> vigentes(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        Map<LocalDate, DiaVigente> porData = new TreeMap<>();
        List<RelatorioRh> doMaisAntigo = new ArrayList<>(listar(usuarioId));
        java.util.Collections.reverse(doMaisAntigo);
        for (RelatorioRh r : doMaisAntigo) { // do mais antigo para o mais recente: o recente sobrescreve
            if (r.status() == StatusRelatorioRh.ERRO) {
                continue;
            }
            for (DiaRelatorioRh d : dias(r.id())) {
                if (!d.data().isBefore(inicio) && !d.data().isAfter(fim)) {
                    porData.put(d.data(), new DiaVigente(r.id(), d));
                }
            }
        }
        return new ArrayList<>(porData.values());
    }

    @Override
    public Optional<Abrangencia> abrangencia(UUID usuarioId) {
        List<LocalDate> todas = dias.entrySet().stream()
                .filter(e -> relatorios.get(e.getKey()).usuarioId().equals(usuarioId))
                .flatMap(e -> e.getValue().stream()).map(DiaRelatorioRh::data).sorted().toList();
        return todas.isEmpty() ? Optional.empty()
                : Optional.of(new Abrangencia(todas.get(0), todas.get(todas.size() - 1)));
    }
}
