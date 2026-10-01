package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Roteia a batida lida de um comprovante PDF para a jornada do dia e arquiva o PDF:
 * <ol>
 *   <li>busca o registro de {@code data_referencia} (ou cria) e aloca o horário na primeira
 *       coluna disponível em ordem cronológica;</li>
 *   <li>copia o PDF para o armazenamento da aplicação ({@code comprovante_<uuid>.pdf}) e o
 *       vincula ao dia em tb_comprovante, com o tipo de batida que ele comprova.</li>
 * </ol>
 *
 * <p>Idempotente: o mesmo arquivo (hash) é ignorado e a mesma data/hora não gera duas
 * batidas. Todo processamento fica registrado em tb_comprovante_ponto.
 */
@Service
public class ImportarComprovanteUseCase {

    /** Tolerância para diferença entre o relógio do emissor do comprovante e o do servidor. */
    static final Duration TOLERANCIA_RELOGIO = Duration.ofMinutes(5);
    /** Batidas mais próximas que isso de uma já existente são tratadas como a mesma marcação. */
    static final Duration JANELA_DUPLICIDADE = Duration.ofMinutes(1);
    private static final Set<String> CODIGOS_DUPLICIDADE = Set.of("BATIDA_DUPLICADA");
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final RegistroJornadaRepository registros;
    private final ComprovantePontoRepository comprovantes;
    private final ComprovanteArquivadoRepository arquivos;
    private final ArmazenamentoComprovantes armazenamento;
    private final RegrasJornada regras;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ImportarComprovanteUseCase(RegistroJornadaRepository registros, ComprovantePontoRepository comprovantes,
                                      ComprovanteArquivadoRepository arquivos, ArmazenamentoComprovantes armazenamento,
                                      RegrasJornada regras, ApplicationEventPublisher eventos, Clock clock) {
        this.registros = registros;
        this.comprovantes = comprovantes;
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.regras = regras;
        this.eventos = eventos;
        this.clock = clock;
    }

    /**
     * @param nomeArquivo    nome original do PDF
     * @param hashSha256     hash do conteúdo (deduplicação e integridade)
     * @param dataHoraBatida data/hora extraída; vazio se o padrão não foi encontrado
     * @param conteudo       bytes do PDF lidos sob trava (arquivados se a batida for aceita)
     */
    public record Comprovante(String nomeArquivo, String hashSha256, Optional<LocalDateTime> dataHoraBatida,
                              byte[] conteudo) {
    }

    public record Resultado(StatusImportacao status, String mensagem, RegistroJornadaView registro) {
    }

    /**
     * @param usuarioId dono do comprovante (o da pasta monitorada ou quem enviou o PDF pela tela)
     * @return vazio quando o arquivo já havia sido processado (mesmo hash).
     */
    @Transactional
    public Optional<Resultado> executar(UUID usuarioId, Comprovante comprovante) {
        Optional<ComprovanteImportado> anterior = comprovantes.buscarPorHash(comprovante.hashSha256());
        if (anterior.isPresent()) {
            if (anterior.get().usuarioId().equals(usuarioId)) {
                arquivarRetroativamente(anterior.get(), comprovante);
            }
            return Optional.empty();
        }

        if (comprovante.dataHoraBatida().isEmpty()) {
            return Optional.of(naoImportado(usuarioId, comprovante, null, StatusImportacao.INVALIDO,
                    "Padrão \"Comprovante de Ponto - dd/MM/yyyy HH:mm:ss\" não encontrado no PDF."));
        }

        LocalDateTime dataHora = comprovante.dataHoraBatida().get();
        if (comprovantes.existeImportado(usuarioId, dataHora)) {
            return Optional.of(naoImportado(usuarioId, comprovante, dataHora, StatusImportacao.DUPLICADO,
                    "A batida de %s já foi importada por outro comprovante.".formatted(FORMATO.format(dataHora))));
        }
        if (dataHora.isAfter(LocalDateTime.now(clock).plus(TOLERANCIA_RELOGIO))) {
            return Optional.of(naoImportado(usuarioId, comprovante, dataHora, StatusImportacao.REJEITADO,
                    "Comprovante com data/hora futura (%s).".formatted(FORMATO.format(dataHora))));
        }

        LocalDate data = dataHora.toLocalDate();
        RegistroJornada registro = registros.buscarPorData(usuarioId, data)
                .orElseGet(() -> RegistroJornada.novo(usuarioId, data, regras.classificar(usuarioId, data)));
        MotorCalculoJornadaService motor = regras.motor(usuarioId, data);

        Optional<LocalTime> proxima = registro.getBatidas().batidaProxima(dataHora.toLocalTime(), JANELA_DUPLICIDADE);
        if (proxima.isPresent()) {
            // A batida já existe (ex.: registrada pelo botão "bater ponto"): o PDF ainda serve de prova.
            boolean arquivado = arquivarSeLivre(registro, dataHora, comprovante);
            return Optional.of(naoImportado(usuarioId, comprovante, dataHora, StatusImportacao.DUPLICADO,
                    "Já existe batida às %s neste dia (diferença de até %d min).%s".formatted(
                            HORA.format(proxima.get()), JANELA_DUPLICIDADE.toMinutes(),
                            arquivado ? " O PDF foi arquivado como comprovante dessa batida." : "")));
        }

        try {
            registro.incluirBatida(dataHora.toLocalTime(), motor);
        } catch (RegraNegocioException e) {
            StatusImportacao status = CODIGOS_DUPLICIDADE.contains(e.getCodigo())
                    ? StatusImportacao.DUPLICADO
                    : StatusImportacao.REJEITADO;
            return Optional.of(naoImportado(usuarioId, comprovante, dataHora, status, e.getMessage()));
        }
        registros.salvar(registro);

        TipoBatida posicao = registro.getBatidas().posicaoDe(dataHora.toLocalTime()).orElseThrow();
        arquivar(registro, posicao, dataHora, comprovante);
        reorganizarTipos(registro);

        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        String mensagem = "%s registrada às %s (comprovante %s)."
                .formatted(posicao.rotulo(), HORA.format(dataHora), comprovante.nomeArquivo());

        comprovantes.salvar(ComprovanteImportado.novo(usuarioId, comprovante.nomeArquivo(), comprovante.hashSha256(),
                dataHora, StatusImportacao.IMPORTADO, mensagem));
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, view.data(), OrigemAtualizacao.COMPROVANTE_PDF, view, mensagem));
        return Optional.of(new Resultado(StatusImportacao.IMPORTADO, mensagem, view));
    }

    // ---------------------------------------------------------------- arquivamento

    private void arquivar(RegistroJornada registro, TipoBatida tipo, LocalDateTime dataHora, Comprovante c) {
        if (c.conteudo() == null || c.conteudo().length == 0) {
            return;
        }
        UUID id = UUID.randomUUID();
        String caminho;
        try {
            caminho = armazenamento.armazenar(id, c.conteudo(), dataHora.toLocalDate());
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao arquivar o comprovante " + c.nomeArquivo(), e);
        }
        arquivos.salvar(new ComprovanteArquivado(id, registro.getId(), caminho, tipo, clock.instant(), dataHora,
                c.nomeArquivo(), c.hashSha256(), c.conteudo().length));
    }

    /** Arquiva o PDF de uma batida já existente, se ela ainda não tiver comprovante. */
    private boolean arquivarSeLivre(RegistroJornada registro, LocalDateTime dataHora, Comprovante c) {
        if (c.conteudo() == null || arquivos.existeHash(c.hashSha256())) {
            return false;
        }
        Optional<TipoBatida> tipo = tipoComprovado(registro.getBatidas(), dataHora.toLocalTime());
        boolean ocupado = tipo.isEmpty() || arquivos.listarPorRegistro(registro.getId()).stream()
                .anyMatch(a -> a.tipoBatida() == tipo.get());
        if (ocupado) {
            return false;
        }
        arquivar(registro, tipo.get(), dataHora, c);
        return true;
    }

    /**
     * PDFs importados antes do módulo de armazenamento: ao reaparecerem na pasta (mesmo
     * hash), são arquivados e vinculados ao dia sem gerar nova batida.
     */
    private void arquivarRetroativamente(ComprovanteImportado anterior, Comprovante c) {
        if (anterior.status() != StatusImportacao.IMPORTADO || anterior.dataHoraBatida() == null) {
            return;
        }
        LocalDateTime dataHora = anterior.dataHoraBatida();
        registros.buscarPorData(anterior.usuarioId(), dataHora.toLocalDate())
                .ifPresent(registro -> arquivarSeLivre(registro, dataHora, c));
    }

    /** Após inserir uma batida no meio do dia, as colunas mudam: os vínculos acompanham. */
    private void reorganizarTipos(RegistroJornada registro) {
        VinculoComprovantes.reorganizar(registro, arquivos);
    }

    private static Optional<TipoBatida> tipoComprovado(Batidas batidas, LocalTime horario) {
        return VinculoComprovantes.tipoComprovado(batidas, horario);
    }

    private Resultado naoImportado(UUID usuarioId, Comprovante comprovante, LocalDateTime dataHora,
                                   StatusImportacao status, String mensagem) {
        comprovantes.salvar(ComprovanteImportado.novo(usuarioId, comprovante.nomeArquivo(), comprovante.hashSha256(),
                dataHora, status, mensagem));
        eventos.publishEvent(new ComprovanteNaoImportadoEvento(usuarioId, comprovante.nomeArquivo(), status,
                dataHora, mensagem));
        return new Resultado(status, mensagem, null);
    }
}
