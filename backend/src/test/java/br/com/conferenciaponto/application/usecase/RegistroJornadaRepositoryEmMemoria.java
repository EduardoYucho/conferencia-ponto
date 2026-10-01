package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Fake da porta de persistência para testes de casos de uso sem banco. */
class RegistroJornadaRepositoryEmMemoria implements RegistroJornadaRepository {

    /** Chave: usuário + data (um registro por dia de cada usuário). */
    private record Chave(UUID usuarioId, LocalDate data) {
    }

    private final Map<Chave, RegistroJornada> registros = new LinkedHashMap<>();
    private final List<SaldoMensal> consolidacao = new ArrayList<>();

    @Override
    public Optional<RegistroJornada> buscarPorData(UUID usuarioId, LocalDate data) {
        return Optional.ofNullable(registros.get(new Chave(usuarioId, data)));
    }

    @Override
    public Optional<RegistroJornada> buscarPorId(UUID id) {
        return registros.values().stream().filter(r -> r.getId().equals(id)).findFirst();
    }

    @Override
    public List<RegistroJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return registros.values().stream()
                .filter(r -> r.getUsuarioId().equals(usuarioId))
                .filter(r -> !r.getDataReferencia().isBefore(inicio) && !r.getDataReferencia().isAfter(fim))
                .sorted(Comparator.comparing(RegistroJornada::getDataReferencia))
                .toList();
    }

    @Override
    public List<RegistroJornada> listarPorData(LocalDate data) {
        return registros.values().stream().filter(r -> r.getDataReferencia().equals(data)).toList();
    }

    @Override
    public List<UUID> usuariosComRegistros() {
        return registros.keySet().stream().map(Chave::usuarioId).distinct().toList();
    }

    @Override
    public RegistroJornada salvar(RegistroJornada registro) {
        registros.put(new Chave(registro.getUsuarioId(), registro.getDataReferencia()), registro);
        return registro;
    }

    @Override
    public void excluir(RegistroJornada registro) {
        registros.remove(new Chave(registro.getUsuarioId(), registro.getDataReferencia()));
    }

    @Override
    public List<SaldoMensal> consolidarAno(UUID usuarioId, int ano) {
        return consolidacao.stream().filter(s -> s.ano() == ano).toList();
    }

    @Override
    public List<SaldoMensal> consolidarPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        Map<YearMonth, List<RegistroJornada>> porMes = new TreeMap<>();
        listarPorPeriodo(usuarioId, inicio, fim).forEach(r ->
                porMes.computeIfAbsent(YearMonth.from(r.getDataReferencia()), m -> new ArrayList<>()).add(r));
        List<SaldoMensal> meses = new ArrayList<>();
        int acumulado = 0;
        for (Map.Entry<YearMonth, List<RegistroJornada>> e : porMes.entrySet()) {
            List<RegistroJornada> fechados = e.getValue().stream().filter(r -> r.getSaldoDiarioSegundos() != null).toList();
            int saldo = fechados.stream().mapToInt(RegistroJornada::getSaldoDiarioSegundos).sum();
            acumulado += saldo;
            meses.add(new SaldoMensal(e.getKey().getYear(), e.getKey().getMonthValue(), e.getValue().size(),
                    e.getValue().size() - fechados.size(),
                    fechados.stream().mapToInt(RegistroJornada::getSegundosTrabalhados).sum(),
                    fechados.stream().mapToInt(RegistroJornada::getJornadaPrevistaSegundos).sum(), saldo, acumulado));
        }
        return meses;
    }

    void definirConsolidacao(List<SaldoMensal> saldos) {
        consolidacao.clear();
        consolidacao.addAll(saldos);
    }

    int quantidade() {
        return registros.size();
    }
}
