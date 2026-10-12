package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
import br.com.conferenciaponto.modulos.atendimento.application.evento.VetoresDeTexto;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.IndiceEmMemoria;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.TextoDoVetor;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Vetores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * A pesquisa por significado da base: o índice em memória (carregado na subida, atualizado a cada vetor), os vetores
 * pendentes gerados em segundo plano com a chave do autor (com novas tentativas e espera crescente) e o vetor da
 * pesquisa com a chave de quem pesquisa (os mesmos textos ficam guardados por um tempo). Os vetores vêm do gerador
 * ({@link VetoresDeTexto}); sem ele, a base funciona só com a pesquisa exata.
 *
 * <p>Trocar o modelo ou o tamanho na configuração: o índice sobe vazio, todos os registros ficam pendentes e os vetores
 * são recalculados aos poucos (os do modelo anterior saem quando o novo é gravado).
 */
@Component
public class VetoresDaBase {

    private static final Logger log = LoggerFactory.getLogger(VetoresDaBase.class);
    private static final int CONSULTAS_GUARDADAS = 300;
    private static final Duration ESPERA_SEM_CHAVE = Duration.ofMinutes(10);
    private static final Duration ESPERA_COTA = Duration.ofHours(1);
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(30);

    private final Vetores vetores;
    private final ObjectProvider<VetoresDeTexto> gerador;
    private final ConfiguracaoDosVetores configuracao;
    private final Clock clock;
    private final IndiceEmMemoria indice;
    private final AtomicBoolean carregado = new AtomicBoolean();
    private final ExecutorService acordador = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "base-vetores");
        t.setDaemon(true);
        return t;
    });

    /** Quem não tem chave (ou está sem cota) espera antes de tentar de novo: não adianta chamar a cada rodada. */
    private final Map<UUID, Instant> autoresEmEspera = new ConcurrentHashMap<>();
    /** Registros que o Google recusou: tentam de novo com espera crescente. */
    private final Map<UUID, Espera> registrosEmEspera = new ConcurrentHashMap<>();
    private volatile Instant pausaAte = Instant.MIN;
    private volatile int falhasSeguidas;
    private final Map<String, float[]> consultas = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> maisAntiga) {
            return size() > CONSULTAS_GUARDADAS;
        }
    };

    private record Espera(int vezes, Instant ate) {
    }

    @Autowired
    public VetoresDaBase(Vetores vetores, ObjectProvider<VetoresDeTexto> gerador, ConfiguracaoDosVetores configuracao,
                         Clock clock) {
        this.vetores = vetores;
        this.gerador = gerador;
        this.configuracao = configuracao;
        this.clock = clock;
        this.indice = new IndiceEmMemoria(configuracao.modelo(), configuracao.dimensao());
    }

    /** O vetor da pesquisa, ou o aviso de por que a pesquisa ficou só exata. */
    public record Consulta(float[] vetor, String aviso) {

        public boolean ativa() {
            return vetor != null;
        }
    }

    // ------------------------------------------------------------------------------------------------ índice

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        carregar();
        acordar();
    }

    /** Lê do banco os vetores do modelo e do tamanho atuais. */
    public void carregar() {
        List<Vetores.Guardado> guardados = vetores.doModelo(configuracao.modelo(), configuracao.dimensao());
        for (Vetores.Guardado g : guardados) {
            if (g.valores().length == configuracao.dimensao()) {
                indice.colocar(g.registroId(), g.valores());
            }
        }
        carregado.set(true);
        log.info("Base de conhecimento: {} vetor(es) de {} ({} valores) na memória; {} registro(s) sem vetor",
                indice.tamanho(), configuracao.modelo(), configuracao.dimensao(), pendentes());
    }

    /** O registro já tem vetor (entra na pesquisa por significado). */
    public boolean tem(UUID registroId) {
        return indice.tem(registroId);
    }

    public boolean disponivel() {
        return gerador.getIfAvailable() != null;
    }

    public long pendentes() {
        return vetores.contarPendentes(configuracao.modelo(), configuracao.dimensao());
    }

    /** O registro foi apagado. */
    public void esquecer(UUID registroId) {
        indice.tirar(registroId);
        registrosEmEspera.remove(registroId);
    }

    /**
     * O texto do registro pode ter mudado (edição): se o vetor era de outro texto, ele sai e o registro volta a ficar
     * pendente (o novo vetor é gerado em seguida, com a chave do autor).
     */
    public void textoMudou(UUID registroId, String titulo, String sintoma, String mensagemErro, String causa, String resolucao) {
        String sha = TextoDoVetor.sha256(TextoDoVetor.documento(titulo, sintoma, mensagemErro, causa, resolucao));
        if (vetores.invalidarSeMudou(registroId, sha)) {
            indice.tirar(registroId);
            registrosEmEspera.remove(registroId);
            depoisDaTransacao();
        }
    }

    /** Um registro novo: gera o vetor logo depois que a criação for gravada. */
    public void registroNovo() {
        depoisDaTransacao();
    }

    public List<Semelhante> semelhantes(float[] consulta, double minimo, int maximo, Predicate<UUID> aceitar) {
        return indice.maisParecidos(consulta, minimo, maximo, aceitar);
    }

    // ------------------------------------------------------------------------------------------------ consulta

    /**
     * O vetor de um texto com a chave da pessoa (o mesmo texto não é calculado de novo enquanto estiver guardado).
     *
     * @param texto já no formato do modelo ({@link TextoDoVetor#consulta} ou {@link TextoDoVetor#documento})
     */
    public Consulta consulta(UUID usuarioId, String texto) {
        VetoresDeTexto vt = gerador.getIfAvailable();
        if (vt == null) {
            return new Consulta(null, "A pesquisa por significado não está disponível neste servidor.");
        }
        if (texto == null || texto.isBlank()) {
            return new Consulta(null, null);
        }
        String chave = configuracao.modelo() + "|" + configuracao.dimensao() + "|" + texto;
        synchronized (consultas) {
            float[] guardada = consultas.get(chave);
            if (guardada != null) {
                return new Consulta(guardada, null);
            }
        }
        try {
            float[] vetor = vt.calcular(usuarioId, configuracao.modelo(), configuracao.dimensao(), texto);
            synchronized (consultas) {
                consultas.put(chave, vetor);
            }
            return new Consulta(vetor, null);
        } catch (FalhaNoVetor falha) {
            log.info("Pesquisa por significado indisponível para {}: {}", usuarioId, falha.motivo());
            return new Consulta(null, avisoDaConsulta(falha));
        } catch (RuntimeException e) {
            log.warn("Pesquisa por significado falhou: {}", e.toString());
            return new Consulta(null, "A pesquisa por significado falhou agora; ficou só a pesquisa exata.");
        }
    }

    private static String avisoDaConsulta(FalhaNoVetor falha) {
        return switch (falha.motivo()) {
            case SEM_CHAVE -> "Só a pesquisa exata: " + primeiraMinuscula(falha.getMessage());
            case CHAVE_RECUSADA, RECUSADO -> "Só a pesquisa exata: " + primeiraMinuscula(falha.getMessage());
            case COTA_DIARIA -> "Só a pesquisa exata: a cota da sua chave do Gemini acabou por hoje.";
            case LIMITE, INDISPONIVEL -> "Só a pesquisa exata: o Gemini não respondeu agora. Pesquise de novo em instantes.";
        };
    }

    private static String primeiraMinuscula(String texto) {
        return texto == null || texto.isEmpty() ? "" : Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }

    // ------------------------------------------------------------------------------------------------ pendentes

    /** O resultado de uma rodada. */
    public record Rodada(int gerados, int falharam, int adiados) {
    }

    @Scheduled(fixedDelayString = "${conhecimento.vetor.intervalo:60s}", initialDelayString = "${conhecimento.vetor.primeira-vez:30s}")
    public void agendada() {
        try {
            gerarPendentes();
        } catch (RuntimeException e) {
            log.warn("Vetores pendentes da base: a rodada falhou ({}); tenta de novo na próxima", e.toString());
        }
    }

    /** Pede uma rodada já (em segundo plano). */
    public void acordar() {
        acordador.execute(() -> {
            try {
                gerarPendentes();
            } catch (RuntimeException e) {
                log.warn("Vetores pendentes da base: a rodada falhou ({})", e.toString());
            }
        });
    }

    private void depoisDaTransacao() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    acordar();
                }
            });
        } else {
            acordar();
        }
    }

    /**
     * Gera os vetores que faltam, com a chave de cada autor. Sem chave ou sem cota, o autor espera um pouco; limite ou
     * Gemini fora do ar, a rodada para e a próxima espera mais.
     */
    public synchronized Rodada gerarPendentes() {
        VetoresDeTexto vt = gerador.getIfAvailable();
        Instant agora = clock.instant();
        if (vt == null || agora.isBefore(pausaAte)) {
            return new Rodada(0, 0, 0);
        }
        if (!carregado.get()) {
            carregar();
        }
        int gerados = 0;
        int falharam = 0;
        int adiados = 0;
        for (Vetores.Pendente p : vetores.pendentes(configuracao.modelo(), configuracao.dimensao(), configuracao.lote() * 10)) {
            if (gerados + falharam >= configuracao.lote()) {
                break;
            }
            if (emEspera(p, agora)) {
                adiados++;
                continue;
            }
            String texto = p.texto();
            try {
                float[] vetor = vt.calcular(p.autorId(), configuracao.modelo(), configuracao.dimensao(), texto);
                vetores.salvar(p.registroId(), configuracao.modelo(), configuracao.dimensao(), vetor,
                        TextoDoVetor.sha256(texto), clock.instant());
                indice.colocar(p.registroId(), vetor);
                registrosEmEspera.remove(p.registroId());
                falhasSeguidas = 0;
                gerados++;
            } catch (FalhaNoVetor falha) {
                if (falha.motivo() == FalhaNoVetor.Motivo.SEM_CHAVE) {
                    adiados++; // nem chegou ao Gemini: não conta no lote
                } else {
                    falharam++;
                }
                if (!tratar(falha, p, agora)) {
                    break;
                }
            }
        }
        if (gerados > 0 || falharam > 0) {
            log.info("Vetores da base: {} gerado(s), {} falha(s), {} adiado(s)", gerados, falharam, adiados);
        }
        return new Rodada(gerados, falharam, adiados);
    }

    private boolean emEspera(Vetores.Pendente p, Instant agora) {
        Instant autor = autoresEmEspera.get(p.autorId());
        if (autor != null && agora.isBefore(autor)) {
            return true;
        }
        Espera registro = registrosEmEspera.get(p.registroId());
        return registro != null && agora.isBefore(registro.ate());
    }

    /** @return se a rodada continua com os próximos */
    private boolean tratar(FalhaNoVetor falha, Vetores.Pendente p, Instant agora) {
        switch (falha.motivo()) {
            case SEM_CHAVE, CHAVE_RECUSADA -> {
                autoresEmEspera.put(p.autorId(), agora.plus(ESPERA_SEM_CHAVE));
                log.debug("Vetor do registro {} adiado: {} (autor {})", p.registroId(), falha.motivo(), p.autorId());
                return true;
            }
            case COTA_DIARIA -> {
                autoresEmEspera.put(p.autorId(), agora.plus(ESPERA_COTA));
                return true;
            }
            case LIMITE -> {
                Duration espera = falha.esperar() == null ? Duration.ofSeconds(30) : falha.esperar();
                pausaAte = agora.plus(espera);
                return false;
            }
            case INDISPONIVEL -> {
                falhasSeguidas++;
                Duration espera = Duration.ofMinutes(1).multipliedBy(1L << Math.min(5, falhasSeguidas - 1));
                pausaAte = agora.plus(espera.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : espera);
                return false;
            }
            default -> {
                Espera anterior = registrosEmEspera.get(p.registroId());
                int vezes = anterior == null ? 1 : anterior.vezes() + 1;
                Duration espera = Duration.ofMinutes(5).multipliedBy(1L << Math.min(4, vezes - 1));
                registrosEmEspera.put(p.registroId(), new Espera(vezes, agora.plus(espera)));
                log.warn("Vetor do registro {} recusado pelo Gemini ({}): tenta de novo em {} min", p.registroId(),
                        falha.getMessage(), espera.toMinutes());
                return true;
            }
        }
    }
}
