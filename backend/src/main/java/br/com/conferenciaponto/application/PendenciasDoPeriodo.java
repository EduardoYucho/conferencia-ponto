package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O que ainda está em aberto num período, para avisar antes de conferir o mês ou fechar o banco de horas: dias com
 * entrada sem saída (ficam fora do saldo), dias úteis sem nenhum registro (também fora do saldo: falta, férias não
 * cadastradas ou um PDF que não chegou) e diferenças com o relatório do RH ainda sem decisão. Só conta o que já
 * passou (hoje ainda está em andamento) e, nos dias sem registro, só a partir do primeiro registro da pessoa: antes
 * dele ela ainda não usava o sistema.
 */
@Component
public class PendenciasDoPeriodo {

    /** Quantas datas de cada tipo entram no texto (as outras viram "e mais N"). */
    static final int DATAS_NO_TEXTO = 5;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    /** Sem pendências (testes que não tratam delas). */
    public static final PendenciasDoPeriodo NENHUMA = new PendenciasDoPeriodo(null, null, null, Clock.systemDefaultZone());

    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final DivergenciaRepository divergencias;
    private final Clock clock;

    public PendenciasDoPeriodo(RegistroJornadaRepository registros, RegrasJornada regras,
                               DivergenciaRepository divergencias, Clock clock) {
        this.registros = registros;
        this.regras = regras;
        this.divergencias = divergencias;
        this.clock = clock;
    }

    /**
     * @param semSaida     dias com entrada sem saída
     * @param semRegistro  dias úteis sem nenhum registro
     * @param divergencias dias com diferença do relatório do RH sem decisão
     */
    public record Pendencias(List<LocalDate> semSaida, List<LocalDate> semRegistro, List<LocalDate> divergencias) {

        public static final Pendencias NENHUMA = new Pendencias(List.of(), List.of(), List.of());

        public Pendencias {
            semSaida = List.copyOf(semSaida);
            semRegistro = List.copyOf(semRegistro);
            divergencias = List.copyOf(divergencias);
        }

        public boolean vazia() {
            return semSaida.isEmpty() && semRegistro.isEmpty() && divergencias.isEmpty();
        }

        /** Uma frase por tipo, com as datas (ex.: "2 dias com entrada sem saída (03/11 e 14/11)"). */
        public List<String> itens() {
            List<String> itens = new ArrayList<>();
            if (!semSaida.isEmpty()) {
                itens.add(quantos(semSaida.size(), "dia com entrada sem saída", "dias com entrada sem saída")
                        + " (" + datas(semSaida) + "): fora do saldo");
            }
            if (!semRegistro.isEmpty()) {
                itens.add(quantos(semRegistro.size(), "dia útil sem nenhum registro", "dias úteis sem nenhum registro")
                        + " (" + datas(semRegistro) + "): fora do saldo");
            }
            if (!divergencias.isEmpty()) {
                itens.add(quantos(divergencias.size(), "diferença com o relatório do RH sem decisão",
                        "diferenças com o relatório do RH sem decisão") + " (" + datas(divergencias) + ")");
            }
            return itens;
        }

        /** Os itens numa frase só (para as mensagens de recusa). */
        public String resumo() {
            List<String> itens = itens();
            if (itens.size() <= 1) {
                return String.join("", itens);
            }
            return String.join("; ", itens.subList(0, itens.size() - 1)) + " e " + itens.get(itens.size() - 1);
        }

        private static String quantos(int n, String um, String varios) {
            return n + " " + (n == 1 ? um : varios);
        }

        private static String datas(List<LocalDate> datas) {
            List<String> texto = datas.stream().limit(DATAS_NO_TEXTO).map(DIA::format).collect(Collectors.toList());
            if (datas.size() > DATAS_NO_TEXTO) {
                return String.join(", ", texto) + " e mais " + (datas.size() - DATAS_NO_TEXTO);
            }
            if (texto.size() == 1) {
                return texto.get(0);
            }
            return String.join(", ", texto.subList(0, texto.size() - 1)) + " e " + texto.get(texto.size() - 1);
        }
    }

    /** As pendências de {@code inicio} a {@code fim} (inclusive), só dos dias que já passaram. */
    public Pendencias de(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        if (registros == null) {
            return Pendencias.NENHUMA;
        }
        LocalDate ontem = LocalDate.now(clock).minusDays(1);
        LocalDate ate = fim.isBefore(ontem) ? fim : ontem;
        if (ate.isBefore(inicio)) {
            return Pendencias.NENHUMA;
        }
        List<RegistroJornada> doPeriodo = registros.listarPorPeriodo(usuarioId, inicio, ate);
        Set<LocalDate> registrados = new TreeSet<>();
        Set<LocalDate> semSaida = new TreeSet<>();
        for (RegistroJornada r : doPeriodo) {
            registrados.add(r.getDataReferencia());
            if (r.getStatus() == StatusJornada.EM_ANDAMENTO) {
                semSaida.add(r.getDataReferencia());
            }
        }
        List<LocalDate> semRegistro = new ArrayList<>();
        // antes do primeiro registro a pessoa não usava o sistema: esses dias não são pendência
        Optional<LocalDate> primeiro = registros.primeiroDia(usuarioId);
        LocalDate desde = primeiro.isEmpty() ? ate.plusDays(1) : primeiro.get().isAfter(inicio) ? primeiro.get() : inicio;
        for (LocalDate d = desde; !d.isAfter(ate); d = d.plusDays(1)) {
            if (!registrados.contains(d) && regras.classificar(usuarioId, d) == TipoDia.UTIL) {
                semRegistro.add(d);
            }
        }
        Set<LocalDate> rh = divergencias.listar(usuarioId, StatusDivergencia.PENDENTE, inicio, ate).stream()
                .map(Divergencia::data).collect(Collectors.toCollection(TreeSet::new));
        return new Pendencias(List.copyOf(semSaida), semRegistro, List.copyOf(rh));
    }
}
