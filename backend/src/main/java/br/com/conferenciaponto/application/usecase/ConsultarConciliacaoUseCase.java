package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.application.view.DivergenciaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository.DiaVigente;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ConsultarConciliacaoUseCase {

    private final RelatorioRhRepository relatorios;
    private final DivergenciaRepository divergencias;
    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;

    public ConsultarConciliacaoUseCase(RelatorioRhRepository relatorios, DivergenciaRepository divergencias,
                                       RegistroJornadaRepository registros, RegrasJornada regras) {
        this.relatorios = relatorios;
        this.divergencias = divergencias;
        this.registros = registros;
        this.regras = regras;
    }

    public List<DivergenciaView> divergencias(UUID usuarioId, StatusDivergencia status, LocalDate inicio,
                                              LocalDate fim) {
        List<Divergencia> lista = divergencias.listar(usuarioId, status, inicio, fim);
        if (lista.isEmpty()) {
            return List.of();
        }
        LocalDate de = lista.stream().map(Divergencia::data).min(LocalDate::compareTo).orElseThrow();
        LocalDate ate = lista.stream().map(Divergencia::data).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, DiaVigente> vigentes = relatorios.vigentes(usuarioId, de, ate).stream()
                .collect(Collectors.toMap(v -> v.dia().data(), Function.identity()));
        Map<LocalDate, RegistroJornada> locais = registros.listarPorPeriodo(usuarioId, de, ate).stream()
                .collect(Collectors.toMap(RegistroJornada::getDataReferencia, Function.identity()));
        Map<UUID, RelatorioRh> porId = relatorios.listar(usuarioId).stream()
                .collect(Collectors.toMap(RelatorioRh::id, Function.identity()));

        List<DivergenciaView> views = new ArrayList<>(lista.size());
        for (Divergencia d : lista) {
            DiaVigente vigente = vigentes.get(d.data());
            RegistroJornada local = locais.get(d.data());
            views.add(new DivergenciaView(d, local == null ? null : RegistroJornadaView.de(local, regras.motor(local)),
                    local == null ? regras.classificar(usuarioId, d.data()) : local.getTipoDia(),
                    vigente == null ? null : vigente.dia(),
                    porId.get(vigente == null ? d.relatorioId() : vigente.relatorioId())));
        }
        return views;
    }

    public ConciliacaoResumoView resumo(UUID usuarioId) {
        List<Divergencia> todas = divergencias.listar(usuarioId, null, null, null);
        Map<TipoDivergencia, Integer> pendentesPorTipo = new EnumMap<>(TipoDivergencia.class);
        Map<StatusDivergencia, Integer> porStatus = new EnumMap<>(StatusDivergencia.class);
        for (Divergencia d : todas) {
            porStatus.merge(d.status(), 1, Integer::sum);
            if (d.isPendente()) {
                pendentesPorTipo.merge(d.tipo(), 1, Integer::sum);
            }
        }

        List<ConciliacaoResumoView.Comparativo> comparativos = new ArrayList<>();
        for (RelatorioRh r : relatorios.listar(usuarioId)) {
            List<DiaRelatorioRh> dias = relatorios.dias(r.id());
            if (dias.isEmpty()) {
                comparativos.add(new ConciliacaoResumoView.Comparativo(r, 0, 0, 0, 0, 0));
                continue;
            }
            LocalDate de = Collections.min(dias.stream().map(DiaRelatorioRh::data).toList());
            LocalDate ate = Collections.max(dias.stream().map(DiaRelatorioRh::data).toList());
            int saldoRh = dias.stream().mapToInt(DiaRelatorioRh::saldoSegundos).sum();
            List<SaldoMensal> local = registros.consolidarPeriodo(usuarioId, de, ate);
            int pendentes = (int) todas.stream()
                    .filter(d -> d.isPendente() && !d.data().isBefore(de) && !d.data().isAfter(ate)).count();
            comparativos.add(new ConciliacaoResumoView.Comparativo(r, dias.size(), saldoRh,
                    local.stream().mapToInt(SaldoMensal::saldoMensalSegundos).sum(),
                    local.stream().mapToInt(SaldoMensal::diasEmAberto).sum(), pendentes));
        }
        return new ConciliacaoResumoView(comparativos, pendentesPorTipo, porStatus);
    }
}
