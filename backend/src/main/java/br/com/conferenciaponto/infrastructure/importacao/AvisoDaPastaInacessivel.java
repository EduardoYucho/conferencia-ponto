package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.usecase.NotificacoesUseCase;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor.Situacao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Avisa no sino quando a pasta dos comprovantes de alguém fica inacessível por mais de {@link #LIMITE} <b>dentro do
 * expediente</b> da pessoa (num dia útil dela, entre a primeira entrada e a última saída do horário). Fora disso a
 * queda não pesa: os PDFs ficam na pasta e entram sozinhos quando ela volta (no uso, a pasta de rede cai toda noite,
 * quando o outro computador desliga). O aviso vai para a dona da pasta e para quem vê os logs, uma vez por dia.
 */
@Component
public class AvisoDaPastaInacessivel {

    private static final Logger log = LoggerFactory.getLogger(AvisoDaPastaInacessivel.class);
    static final Duration LIMITE = Duration.ofHours(1);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");

    private final GerenciadorMonitoresPdf monitores;
    private final RegrasJornada regras;
    private final UsuarioRepository usuarios;
    private final NotificacoesUseCase notificacoes;
    private final Clock clock;

    public AvisoDaPastaInacessivel(GerenciadorMonitoresPdf monitores, RegrasJornada regras, UsuarioRepository usuarios,
                                   NotificacoesUseCase notificacoes, Clock clock) {
        this.monitores = monitores;
        this.regras = regras;
        this.usuarios = usuarios;
        this.notificacoes = notificacoes;
        this.clock = clock;
    }

    /** Nada escapa daqui: uma falha não pode parar as conferências seguintes. */
    @Scheduled(fixedDelayString = "${ponto.importacao-pdf.aviso-da-pasta:10m}", initialDelayString = "5m")
    public void conferir() {
        try {
            int avisos = conferirAgora();
            if (avisos > 0) {
                log.info("Pasta dos comprovantes inacessível no expediente: {} aviso(s) no sino", avisos);
            }
        } catch (RuntimeException | Error e) {
            log.error("A conferência das pastas dos comprovantes falhou (tenta de novo na próxima)", e);
        }
    }

    /** @return quantos avisos novos foram para o sino */
    int conferirAgora() {
        Instant agora = clock.instant();
        ZonedDateTime local = agora.atZone(clock.getZone());
        int avisos = 0;
        for (EstadoMonitor estado : monitores.estados()) {
            if (estado.situacao() != Situacao.INDISPONIVEL || estado.desde() == null) {
                continue;
            }
            try {
                Optional<Instant> inicio = inicioDoExpediente(estado.usuarioId(), local);
                if (inicio.isEmpty()) {
                    continue;
                }
                // conta só o tempo fora do ar dentro do expediente de hoje
                Instant contaDesde = estado.desde().isAfter(inicio.get()) ? estado.desde() : inicio.get();
                if (Duration.between(contaDesde, agora).compareTo(LIMITE) < 0) {
                    continue;
                }
                avisos += avisar(estado, local);
            } catch (RuntimeException e) {
                log.warn("Não deu para conferir a pasta de {}: {}", estado.usuarioId(), e.toString());
            }
        }
        return avisos;
    }

    /** O começo do expediente de hoje, se hoje é dia útil da pessoa e agora está dentro dele. */
    private Optional<Instant> inicioDoExpediente(UUID usuarioId, ZonedDateTime agora) {
        LocalDate hoje = agora.toLocalDate();
        if (regras.classificar(usuarioId, hoje) != TipoDia.UTIL) {
            return Optional.empty();
        }
        Optional<GradeHoraria> grade = regras.horario(usuarioId, hoje).gradeDo(hoje);
        if (grade.isEmpty() || grade.get().periodos().isEmpty()) {
            return Optional.empty();
        }
        List<GradeHoraria.Periodo> periodos = grade.get().periodos();
        LocalTime entrada = periodos.get(0).entrada();
        LocalTime saida = periodos.get(periodos.size() - 1).saida();
        LocalTime hora = agora.toLocalTime();
        if (hora.isBefore(entrada) || hora.isAfter(saida)) {
            return Optional.empty();
        }
        return Optional.of(hoje.atTime(entrada).atZone(agora.getZone()).toInstant());
    }

    private int avisar(EstadoMonitor estado, ZonedDateTime agora) {
        Usuario dono = usuarios.buscarPorId(estado.usuarioId()).filter(Usuario::ativo).orElse(null);
        if (dono == null) {
            return 0;
        }
        ZonedDateTime desde = estado.desde().atZone(agora.getZone());
        String quando = desde.toLocalDate().equals(agora.toLocalDate()) ? HORA.format(desde) : DIA_E_HORA.format(desde);
        String motivo = estado.mensagem() == null ? "" : " (" + estado.mensagem() + ")";
        String depois = " Os PDFs que chegarem nela entram sozinhos quando ela voltar. Se for uma pasta de rede, confira "
                + "se o outro computador está ligado e a conexão (a VPN, por exemplo).";
        Map<UUID, String> mensagens = new LinkedHashMap<>();
        mensagens.put(dono.id(), "A pasta dos seus comprovantes está inacessível desde " + quando + motivo + "." + depois);
        for (Usuario u : usuarios.listar()) {
            if (u.ativo() && u.pode(PermissoesDaAdministracao.LOGS) && !mensagens.containsKey(u.id())) {
                mensagens.put(u.id(), "A pasta dos comprovantes de " + dono.nome() + " está inacessível desde " + quando
                        + motivo + "." + depois);
            }
        }
        String chave = "PASTA_INACESSIVEL:%s:%s".formatted(dono.id(), agora.toLocalDate());
        int novos = 0;
        for (Map.Entry<UUID, String> m : mensagens.entrySet()) {
            String texto = m.getValue().length() <= 500 ? m.getValue() : m.getValue().substring(0, 499) + "…";
            if (notificacoes.notificar(m.getKey(), TipoNotificacao.PASTA, chave, "Pasta dos comprovantes inacessível",
                    texto, "/").isPresent()) {
                novos++;
            }
        }
        return novos;
    }
}
