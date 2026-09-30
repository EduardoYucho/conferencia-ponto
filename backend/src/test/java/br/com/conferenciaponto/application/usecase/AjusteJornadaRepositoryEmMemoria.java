package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class AjusteJornadaRepositoryEmMemoria implements AjusteJornadaRepository {

    final List<AjusteJornada> salvos = new ArrayList<>();

    @Override
    public void salvar(AjusteJornada ajuste) {
        salvos.add(ajuste);
    }

    @Override
    public List<AjusteJornada> listarPorData(LocalDate data) {
        return listarPorPeriodo(data, data);
    }

    @Override
    public List<AjusteJornada> listarPorPeriodo(LocalDate inicio, LocalDate fim) {
        return salvos.stream()
                .filter(a -> !a.data().isBefore(inicio) && !a.data().isAfter(fim))
                .sorted(Comparator.comparing(AjusteJornada::ajustadoEm).reversed())
                .toList();
    }
}
