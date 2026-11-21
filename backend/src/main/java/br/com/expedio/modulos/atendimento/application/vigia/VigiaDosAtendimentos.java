package br.com.expedio.modulos.atendimento.application.vigia;

import br.com.expedio.application.evento.AvisoDeModulo;
import br.com.expedio.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.AtendimentoParaOVigia;
import br.com.expedio.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.expedio.modulos.atendimento.domain.fila.Tarefas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Para nenhum atendimento ficar esquecido (em outubro, cinco ficaram parados até a limpeza apagar os arquivos). A
 * cada {@code atendimento.vigia.intervalo} (10 min):
 * <ul>
 *   <li><b>retoma sozinho</b> a pausa cujo motivo costuma passar: a IA local ou o Gemini que não respondeu e o disco
 *       cheio (este, só com espaço de novo) depois de 30 min, 1 h, 2 h... até 8 h entre uma tentativa e outra; a cota
 *       diária do Gemini, depois que ela renova (meia-noite no Pacífico). A chave recusada, os créditos e o modelo que
 *       falta não passam sozinhos: ficam com a pessoa;</li>
 *   <li>avisa no sino do dono o atendimento <b>parado há mais de 1 dia</b> (pausado ou nunca processado);</li>
 *   <li>avisa no sino, 3 dias antes, que a <b>limpeza vai apagar os arquivos</b> de um atendimento sem texto pronto.</li>
 * </ul>
 * Cada aviso sai uma vez (a chave dele). Nada escapa daqui: um atendimento com problema não impede os outros.
 */
@Component
public class VigiaDosAtendimentos {

    private static final Logger log = LoggerFactory.getLogger(VigiaDosAtendimentos.class);

    static final Duration PARADO_HA = Duration.ofDays(1);
    static final Duration AVISO_DA_LIMPEZA = Duration.ofDays(3);
    static final Duration PRIMEIRA_ESPERA = Duration.ofMinutes(30);
    static final Duration MAIOR_ESPERA = Duration.ofHours(8);
    /** Depois disso sem retomar sozinho, a contagem das tentativas recomeça. */
    static final Duration MEMORIA_DAS_TENTATIVAS = Duration.ofDays(1);
    /** A cota diária do Gemini renova à meia-noite no Pacífico; 10 min de folga. */
    static final ZoneId FUSO_DA_COTA = ZoneId.of("America/Los_Angeles");
    static final Duration FOLGA_DA_COTA = Duration.ofMinutes(10);
    static final String COTA_DIARIA = "GEMINI_COTA_DIARIA";
    static final String DISCO_CHEIO = "DISCO_CHEIO";
    /** Motivos que passam com o tempo (a IA voltou, a internet voltou, o limite por minuto passou). */
    static final Set<String> PASSAGEIROS = Set.of("IA_LOCAL_PARADA", "IA_LOCAL_INDISPONIVEL", "GEMINI_INDISPONIVEL",
            "GEMINI_LIMITE", DISCO_CHEIO, COTA_DIARIA);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");

    /** O que uma volta do vigia fez. */
    record Volta(int retomados, int avisos) {
    }

    private record Tentativas(int vezes, Instant ultima) {
    }

    private final Atendimentos atendimentos;
    private final Tarefas tarefas;
    private final ProcessarAtendimentos processamento;
    private final PastaDosAtendimentos pasta;
    private final long espacoMinimo;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    private final Map<UUID, Tentativas> retomadas = new ConcurrentHashMap<>();
    /** Os avisos já pedidos desde a subida (para não pedir de novo a cada volta). */
    private final Set<String> avisados = ConcurrentHashMap.newKeySet();

    @Autowired
    public VigiaDosAtendimentos(Atendimentos atendimentos, Tarefas tarefas, ProcessarAtendimentos processamento,
                                PastaDosAtendimentos pasta,
                                @Value("${atendimento.armazenamento.espaco-minimo:200MB}") DataSize espacoMinimo,
                                ApplicationEventPublisher eventos, Clock clock) {
        this(atendimentos, tarefas, processamento, pasta, espacoMinimo.toBytes(), eventos, clock);
    }

    VigiaDosAtendimentos(Atendimentos atendimentos, Tarefas tarefas, ProcessarAtendimentos processamento,
                         PastaDosAtendimentos pasta, long espacoMinimo, ApplicationEventPublisher eventos, Clock clock) {
        this.atendimentos = atendimentos;
        this.tarefas = tarefas;
        this.processamento = processamento;
        this.pasta = pasta;
        this.espacoMinimo = espacoMinimo;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${atendimento.vigia.intervalo:10m}", initialDelayString = "${atendimento.vigia.primeira-vez:2m}")
    public void vigiar() {
        try {
            Volta volta = vigiarAgora();
            if (volta.retomados() + volta.avisos() > 0) {
                log.info("Vigia dos atendimentos: {} retomado(s) sozinho(s), {} aviso(s) no sino", volta.retomados(),
                        volta.avisos());
            }
        } catch (RuntimeException | Error e) {
            log.error("O vigia dos atendimentos falhou (tenta de novo na próxima volta)", e);
        }
    }

    Volta vigiarAgora() {
        Instant agora = clock.instant();
        retomadas.values().removeIf(t -> t.ultima().plus(MEMORIA_DAS_TENTATIVAS).isBefore(agora));
        int retomados = 0;
        int avisos = 0;
        for (AtendimentoParaOVigia a : atendimentos.paraOVigia(agora.plus(AVISO_DA_LIMPEZA))) {
            try {
                if (retomarSePassou(a, agora)) {
                    retomados++;
                    continue;
                }
                avisos += avisarSeParado(a, agora) ? 1 : 0;
                avisos += avisarDaLimpeza(a, agora) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("O vigia não conseguiu olhar o atendimento {}: {}", a.id(), e.toString());
            }
        }
        return new Volta(retomados, avisos);
    }

    // ------------------------------------------------------------------------------------------- retomar sozinho

    private boolean retomarSePassou(AtendimentoParaOVigia a, Instant agora) {
        if (a.situacao() != SituacaoDoAtendimento.PAUSADO || a.arquivosApagadosEm() != null) {
            return false;
        }
        Set<String> motivos = tarefas.motivosDaPausa(a.id());
        if (motivos.isEmpty() || !PASSAGEIROS.containsAll(motivos)) {
            return false; // sem motivo conhecido, ou um que só a pessoa resolve (chave, créditos, modelo)
        }
        Instant quando = quandoRetomar(a, motivos);
        if (agora.isBefore(quando)) {
            return false;
        }
        if (motivos.contains(DISCO_CHEIO) && pasta.espacoLivre() < espacoMinimo) {
            return false;
        }
        if (!processamento.retomarSozinho(a.id())) {
            return false;
        }
        Tentativas antes = retomadas.get(a.id());
        retomadas.put(a.id(), new Tentativas(antes == null ? 1 : antes.vezes() + 1, agora));
        log.info("Atendimento {} retomado sozinho (pausado por {} desde {})", a.id(), String.join(", ", motivos),
                a.atualizadoEm());
        return true;
    }

    /** A cota, depois que ela renova; o resto, com a espera dobrando a cada vez que o vigia retomou e pausou de novo. */
    Instant quandoRetomar(AtendimentoParaOVigia a, Set<String> motivos) {
        Instant pausadoEm = a.atualizadoEm();
        Tentativas antes = retomadas.get(a.id());
        int vezes = antes == null ? 0 : antes.vezes();
        Duration espera = PRIMEIRA_ESPERA.multipliedBy(1L << Math.min(vezes, 10));
        if (espera.compareTo(MAIOR_ESPERA) > 0) {
            espera = MAIOR_ESPERA;
        }
        Instant quando = pausadoEm.plus(espera);
        if (motivos.contains(COTA_DIARIA)) {
            ZonedDateTime renova = pausadoEm.atZone(FUSO_DA_COTA).toLocalDate().plusDays(1).atStartOfDay(FUSO_DA_COTA);
            Instant depoisDaCota = renova.toInstant().plus(FOLGA_DA_COTA);
            quando = depoisDaCota.isAfter(quando) ? depoisDaCota : quando;
        }
        return quando;
    }

    // ------------------------------------------------------------------------------------------- avisos

    private boolean avisarSeParado(AtendimentoParaOVigia a, Instant agora) {
        boolean novo = a.situacao() == SituacaoDoAtendimento.NOVO;
        if (!novo && a.situacao() != SituacaoDoAtendimento.PAUSADO) {
            return false;
        }
        Instant desde = novo ? a.criadoEm() : a.atualizadoEm();
        if (desde == null || agora.isBefore(desde.plus(PARADO_HA))) {
            return false;
        }
        String chamado = a.chamado();
        String mensagem;
        if (a.arquivosApagadosEm() != null) {
            mensagem = (novo ? "O atendimento do chamado %s nunca foi processado" : "O atendimento do chamado %s ficou pausado")
                    .formatted(chamado) + " e a limpeza de 30 dias já apagou os arquivos dele (em "
                    + dia(a.arquivosApagadosEm()) + "). Abra-o para gerar o texto só com a conversa ou para apagá-lo.";
        } else if (novo) {
            mensagem = "O atendimento do chamado %s foi criado em %s e ainda não foi processado. Os links dos anexos do "
                    .formatted(chamado, dia(a.criadoEm())) + "Digisac valem 24 horas: se venceram, exporte a conversa de "
                    + "novo e envie o PDF no atendimento.";
        } else {
            String motivo = a.motivoPausa() == null ? "" : " Motivo: " + a.motivoPausa();
            mensagem = "O atendimento do chamado %s está pausado desde %s.%s Abra-o para retomar."
                    .formatted(chamado, dia(desde), motivo);
        }
        return avisar(a, "ATENDIMENTO_PARADO:%s:%s".formatted(a.id(), LocalDate.ofInstant(desde, clock.getZone())),
                "Atendimento parado há mais de 1 dia", mensagem);
    }

    private boolean avisarDaLimpeza(AtendimentoParaOVigia a, Instant agora) {
        Instant apagar = a.apagarArquivosEm();
        if (apagar == null || a.arquivosApagadosEm() != null || a.textoPronto()
                || a.situacao() == SituacaoDoAtendimento.CANCELADO
                || !apagar.isAfter(agora) || apagar.isAfter(agora.plus(AVISO_DA_LIMPEZA))) {
            return false;
        }
        return avisar(a, "ATENDIMENTO_LIMPEZA:" + a.id(), "Os arquivos de um atendimento sem texto vão ser apagados",
                ("O atendimento do chamado %s ainda não tem texto pronto, e a limpeza de 30 dias apaga os arquivos dele "
                        + "(anexos, ligações e vídeos) em %s. Gere o texto antes disso.").formatted(a.chamado(), dia(apagar)));
    }

    /** @return true se o pedido saiu agora (o ponto ainda confere a chave: o mesmo aviso não se repete no sino) */
    private boolean avisar(AtendimentoParaOVigia a, String chave, String titulo, String mensagem) {
        if (avisados.contains(chave)) {
            return false;
        }
        eventos.publishEvent(new AvisoDeModulo(a.usuarioId(), chave, titulo, mensagem, "/atendimentos/" + a.id()));
        avisados.add(chave);
        return true;
    }

    private String dia(Instant instante) {
        return DIA.format(instante.atZone(clock.getZone()));
    }
}
