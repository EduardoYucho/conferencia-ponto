package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Agregado: registro de jornada de uma data de um usuário.
 *
 * <p>Os campos {@code jornadaPrevistaSegundos}, {@code segundosTrabalhados} e
 * {@code saldoDiarioSegundos} são sempre recalculados pelo motor a cada mudança
 * nas batidas — nunca atribuídos diretamente. Durações em segundos, como no relatório do RH.
 *
 * <p>{@code horariosAjustados} marca as batidas incluídas ou corrigidas manualmente
 * (ex.: falha no relógio corrigida pelo RH). A marcação acompanha o horário, então
 * continua correta quando as batidas mudam de posição.
 */
public class RegistroJornada {

    private final UUID id;
    private final UUID usuarioId;
    private final LocalDate dataReferencia;
    private TipoDia tipoDia;
    private Batidas batidas;
    private boolean registroManual;
    private SortedSet<LocalTime> horariosAjustados;
    private int jornadaPrevistaSegundos;
    private int segundosTrabalhados;
    private Integer saldoDiarioSegundos;

    private RegistroJornada(UUID id, UUID usuarioId, LocalDate dataReferencia, TipoDia tipoDia, Batidas batidas,
                            boolean registroManual, Set<LocalTime> horariosAjustados,
                            int jornadaPrevistaSegundos, int segundosTrabalhados, Integer saldoDiarioSegundos) {
        this.id = Objects.requireNonNull(id, "id");
        this.usuarioId = Objects.requireNonNull(usuarioId, "usuarioId");
        this.dataReferencia = Objects.requireNonNull(dataReferencia, "dataReferencia");
        this.tipoDia = Objects.requireNonNull(tipoDia, "tipoDia");
        this.batidas = Objects.requireNonNull(batidas, "batidas");
        this.registroManual = registroManual;
        this.horariosAjustados = new TreeSet<>(horariosAjustados == null ? Set.of() : horariosAjustados);
        this.jornadaPrevistaSegundos = jornadaPrevistaSegundos;
        this.segundosTrabalhados = segundosTrabalhados;
        this.saldoDiarioSegundos = saldoDiarioSegundos;
    }

    public static RegistroJornada novo(UUID usuarioId, LocalDate data, TipoDia tipoDia) {
        return new RegistroJornada(UUID.randomUUID(), usuarioId, data, tipoDia, Batidas.vazia(), false, Set.of(),
                0, 0, null);
    }

    /** Reconstitui um registro já persistido, com as batidas ajustadas manualmente. */
    public static RegistroJornada restaurar(UUID id, UUID usuarioId, LocalDate data, TipoDia tipoDia, Batidas batidas,
                                            boolean registroManual, Set<LocalTime> horariosAjustados,
                                            int jornadaPrevistaSegundos, int segundosTrabalhados,
                                            Integer saldoDiarioSegundos) {
        return new RegistroJornada(id, usuarioId, data, tipoDia, batidas, registroManual, horariosAjustados,
                jornadaPrevistaSegundos, segundosTrabalhados, saldoDiarioSegundos);
    }

    /** Registra a próxima batida do dia (Entrada 1 &rarr; Saída 1 &rarr; Entrada 2 &rarr; Saída 2). */
    public ApuracaoDiaria registrarBatida(LocalTime horario, MotorCalculoJornadaService motor) {
        this.batidas = batidas.comBatida(horario);
        return recalcular(motor);
    }

    /**
     * Inclui uma batida na posição cronológica correta (importação de comprovantes).
     * Diferente de {@link #registrarBatida}, aceita horários anteriores à última batida.
     */
    public ApuracaoDiaria incluirBatida(LocalTime horario, MotorCalculoJornadaService motor) {
        this.batidas = batidas.comBatidaOrdenada(horario);
        return recalcular(motor);
    }

    /**
     * Lançamento manual de intervalos. Regras, nesta ordem:
     * <ol>
     *   <li>permitido apenas em dias não úteis (fim de semana/feriado);</li>
     *   <li>não sobrescreve batidas de relógio (somente um lançamento manual anterior);</li>
     *   <li>exige intervalos completos.</li>
     * </ol>
     * Marca o registro com a flag de auditoria {@code registroManual}.
     */
    public ApuracaoDiaria lancarManualmente(Batidas novasBatidas, MotorCalculoJornadaService motor) {
        if (tipoDia.isUtil()) {
            throw new RegraNegocioException("LANCAMENTO_MANUAL_DIA_UTIL",
                    "Lançamento manual é permitido apenas em fins de semana e feriados (%s é dia útil)."
                            .formatted(dataReferencia));
        }
        if (!registroManual && !batidas.isVazia()) {
            throw new ConflitoException("REGISTRO_RELOGIO_EXISTENTE",
                    "Já existem batidas de relógio em %s. Exclua-as antes de lançar manualmente."
                            .formatted(dataReferencia));
        }
        if (novasBatidas.isJornadaAberta() || novasBatidas.isVazia()) {
            throw new RegraNegocioException("LANCAMENTO_MANUAL_INCOMPLETO",
                    "Lançamento manual exige intervalos completos (entrada e saída).");
        }
        this.batidas = novasBatidas;
        this.registroManual = true;
        return recalcular(motor);
    }

    /**
     * Ajuste manual das batidas do dia (ex.: o relógio falhou e o RH corrigiu no sistema dele).
     * Recebe a lista FINAL de horários do dia, em qualquer ordem. Regras:
     * <ul>
     *   <li>de 1 a 6 batidas, com pelo menos 1 minuto entre elas;</li>
     *   <li>batidas com comprovante em PDF ({@code comprovadas}) não podem ser removidas nem alteradas;</li>
     *   <li>precisa mudar alguma coisa.</li>
     * </ul>
     * Batidas novas ou alteradas passam a ser marcadas como ajustadas; as que já existiam mantêm a
     * origem que tinham.
     *
     * @return horários do dia antes do ajuste (para o histórico)
     */
    public List<LocalTime> ajustarBatidas(List<LocalTime> horarios, Set<LocalTime> comprovadas,
                                          MotorCalculoJornadaService motor) {
        return ajustarBatidas(horarios, comprovadas, RegrasAjuste.MANUAL, motor);
    }

    /**
     * Regras de um ajuste de batidas.
     *
     * @param realinhamentoSegundos     diferença máxima para uma batida com PDF ter os segundos alinhados a
     *                                  outro registro da mesma batida (0 = batida com PDF não muda)
     * @param espacamentoMinimoSegundos distância mínima entre batidas (evita clique duplo no ajuste manual)
     * @param marcarNovas               batidas novas recebem a marca de ajuste
     */
    public record RegrasAjuste(long realinhamentoSegundos, long espacamentoMinimoSegundos, boolean marcarNovas) {

        public static final RegrasAjuste MANUAL = new RegrasAjuste(0, 60, true);

        /**
         * Dados do relatório do RH (registro oficial): batidas com PDF podem ser alinhadas até 1 minuto,
         * batidas próximas são aceitas como o RH registrou e, num dia que não existia, nada é marcado.
         */
        public static RegrasAjuste conformeRh(boolean diaJaExistia) {
            return new RegrasAjuste(60, 1, diaJaExistia);
        }
    }

    /**
     * Igual ao ajuste manual, mas uma batida com PDF pode ter os segundos alinhados a outro registro da
     * mesma batida (ex.: o relatório do RH marca 08:05:52 e o comprovante, 08:05:53). A batida
     * realinhada continua sendo a batida comprovada: não recebe a marca de ajuste.
     *
     */
    public List<LocalTime> ajustarBatidas(List<LocalTime> horarios, Set<LocalTime> comprovadas, RegrasAjuste regras,
                                          MotorCalculoJornadaService motor) {
        long realinhamentoSegundos = regras.realinhamentoSegundos();
        boolean marcarNovas = regras.marcarNovas();
        if (horarios == null || horarios.isEmpty()) {
            throw new RegraNegocioException("AJUSTE_SEM_BATIDAS",
                    "Informe ao menos uma batida. Para apagar o dia inteiro, use a exclusão.");
        }
        if (horarios.stream().anyMatch(Objects::isNull)) {
            throw new RegraNegocioException("AJUSTE_BATIDA_SEM_HORARIO", "Há batida sem horário informado.");
        }
        List<LocalTime> novos = new ArrayList<>(horarios.stream().map(h -> h.truncatedTo(ChronoUnit.SECONDS)).toList());
        Collections.sort(novos);
        if (novos.size() > TipoBatida.MAXIMO) {
            throw new RegraNegocioException("AJUSTE_MAIS_DE_6_BATIDAS",
                    "O dia comporta no máximo %d batidas.".formatted(TipoBatida.MAXIMO));
        }
        for (int i = 1; i < novos.size(); i++) {
            if (ChronoUnit.SECONDS.between(novos.get(i - 1), novos.get(i)) < regras.espacamentoMinimoSegundos()) {
                throw new RegraNegocioException("AJUSTE_BATIDAS_PROXIMAS", regras.espacamentoMinimoSegundos() >= 60
                        ? "As batidas %s e %s têm menos de 1 minuto de diferença.".formatted(novos.get(i - 1), novos.get(i))
                        : "Batidas repetidas: %s.".formatted(novos.get(i)));
            }
        }
        Set<LocalTime> realinhadas = new TreeSet<>();
        for (LocalTime comprovada : new TreeSet<>(comprovadas)) {
            LocalTime exata = comprovada.truncatedTo(ChronoUnit.SECONDS);
            if (novos.contains(exata)) {
                continue;
            }
            LocalTime proxima = novos.stream()
                    .filter(n -> Math.abs(ChronoUnit.SECONDS.between(exata, n)) <= realinhamentoSegundos)
                    .findFirst().orElse(null);
            if (proxima != null && realinhamentoSegundos > 0) {
                realinhadas.add(proxima);
            } else {
                throw new RegraNegocioException("AJUSTE_ALTERA_BATIDA_COMPROVADA",
                        "A batida das %s tem comprovante em PDF e não pode ser alterada nem removida."
                                .formatted(comprovada));
            }
        }
        List<LocalTime> anteriores = batidas.horarios();
        if (anteriores.equals(novos)) {
            throw new RegraNegocioException("AJUSTE_SEM_MUDANCA", "Nenhuma batida foi alterada.");
        }

        Batidas ajustadas = Batidas.deHorarios(novos); // valida sequência e cronologia
        SortedSet<LocalTime> marcadas = new TreeSet<>();
        for (LocalTime horario : novos) {
            boolean nova = !anteriores.contains(horario) && !realinhadas.contains(horario);
            if ((nova && marcarNovas) || horariosAjustados.contains(horario)) {
                marcadas.add(horario);
            }
        }
        this.batidas = ajustadas;
        this.horariosAjustados = marcadas;
        recalcular(motor);
        return anteriores;
    }

    /**
     * Muda o tipo do dia (ex.: férias cadastradas depois de o dia existir) e recalcula.
     *
     * @return {@code true} se o tipo mudou
     */
    public boolean reclassificar(TipoDia novoTipo, MotorCalculoJornadaService motor) {
        Objects.requireNonNull(novoTipo, "novoTipo");
        if (novoTipo == tipoDia) {
            return false;
        }
        this.tipoDia = novoTipo;
        recalcular(motor);
        return true;
    }

    /** Apuração somente leitura (não altera o estado). */
    public ApuracaoDiaria apurar(MotorCalculoJornadaService motor) {
        return motor.apurar(tipoDia, batidas);
    }

    private ApuracaoDiaria recalcular(MotorCalculoJornadaService motor) {
        ApuracaoDiaria apuracao = motor.apurar(tipoDia, batidas);
        this.jornadaPrevistaSegundos = apuracao.jornadaPrevistaSegundos();
        this.segundosTrabalhados = apuracao.segundosTrabalhados();
        this.saldoDiarioSegundos = apuracao.saldoDiarioSegundos();
        return apuracao;
    }

    /**
     * Reavalia o dia com o tipo e o horário atuais (ex.: depois de mudar o horário de trabalho).
     *
     * @return {@code true} se o tipo ou algum valor gravado mudou
     */
    public boolean reavaliar(TipoDia tipoAtual, MotorCalculoJornadaService motor) {
        boolean mudouTipo = tipoAtual != tipoDia;
        this.tipoDia = Objects.requireNonNull(tipoAtual, "tipoAtual");
        return atualizarCalculo(motor) || mudouTipo;
    }

    /**
     * Refaz o cálculo com as regras atuais (ex.: depois de mudar a regra de tolerância).
     *
     * @return {@code true} se algum valor gravado mudou
     */
    public boolean atualizarCalculo(MotorCalculoJornadaService motor) {
        int previstaAntes = jornadaPrevistaSegundos;
        int trabalhadosAntes = segundosTrabalhados;
        Integer saldoAntes = saldoDiarioSegundos;
        recalcular(motor);
        return previstaAntes != jornadaPrevistaSegundos || trabalhadosAntes != segundosTrabalhados
                || !Objects.equals(saldoAntes, saldoDiarioSegundos);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public LocalDate getDataReferencia() {
        return dataReferencia;
    }

    public TipoDia getTipoDia() {
        return tipoDia;
    }

    public Batidas getBatidas() {
        return batidas;
    }

    public boolean isRegistroManual() {
        return registroManual;
    }

    /** Horários incluídos ou corrigidos manualmente (ordem cronológica). */
    public SortedSet<LocalTime> getHorariosAjustados() {
        return Collections.unmodifiableSortedSet(horariosAjustados);
    }

    public int getJornadaPrevistaSegundos() {
        return jornadaPrevistaSegundos;
    }

    public int getSegundosTrabalhados() {
        return segundosTrabalhados;
    }

    public Integer getSaldoDiarioSegundos() {
        return saldoDiarioSegundos;
    }

    public StatusJornada getStatus() {
        return batidas.isJornadaAberta() ? StatusJornada.EM_ANDAMENTO : StatusJornada.FECHADA;
    }
}
