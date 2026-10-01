package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

class AjusteJornadaRepositoryEmMemoria implements AjusteJornadaRepository {

    final List<AjusteJornada> salvos = new ArrayList<>();

    @Override
    public void salvar(AjusteJornada ajuste) {
        salvos.add(ajuste);
    }

    @Override
    public List<AjusteJornada> listarPorData(UUID usuarioId, LocalDate data) {
        return listarPorPeriodo(usuarioId, data, data);
    }

    @Override
    public List<AjusteJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return salvos.stream()
                .filter(a -> a.usuarioId().equals(usuarioId))
                .filter(a -> !a.data().isBefore(inicio) && !a.data().isAfter(fim))
                .sorted(Comparator.comparing(AjusteJornada::ajustadoEm).reversed())
                .toList();
    }
}
