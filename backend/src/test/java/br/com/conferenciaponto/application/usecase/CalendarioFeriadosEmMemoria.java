package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class CalendarioFeriadosEmMemoria implements CalendarioFeriados {

    private final Map<LocalDate, Feriado> feriados = new TreeMap<>();

    @Override
    public boolean isFeriado(LocalDate data) {
        return feriados.containsKey(data);
    }

    @Override
    public void cadastrar(LocalDate data, String descricao) {
        feriados.putIfAbsent(data, new Feriado(data, descricao));
    }

    @Override
    public void salvar(Feriado feriado) {
        feriados.put(feriado.data(), feriado);
    }

    @Override
    public void excluir(LocalDate data) {
        feriados.remove(data);
    }

    @Override
    public List<Feriado> listar(LocalDate inicio, LocalDate fim) {
        return feriados.values().stream().filter(f -> !f.data().isBefore(inicio) && !f.data().isAfter(fim)).toList();
    }
}
