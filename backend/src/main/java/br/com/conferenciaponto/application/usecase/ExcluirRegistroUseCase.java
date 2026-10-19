package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Remove o registro de uma data (correção de lançamentos). Dias com comprovante PDF
 * arquivado não podem ser excluídos: o comprovante oficial é a trilha de auditoria. A exclusão fica no histórico
 * do dia (as batidas que ele tinha, quem e quando).
 */
@Service
public class ExcluirRegistroUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RegistroJornadaRepository repository;
    private final ComprovanteArquivadoRepository arquivos;
    private final AjusteJornadaRepository historico;
    private final TravaDosMesesConferidos trava;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ExcluirRegistroUseCase(RegistroJornadaRepository repository, ComprovanteArquivadoRepository arquivos,
                                  AjusteJornadaRepository historico, TravaDosMesesConferidos trava,
                                  ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.arquivos = arquivos;
        this.historico = historico;
        this.trava = trava;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public void executar(UUID usuarioId, LocalDate data) {
        executar(usuarioId, data, "sistema");
    }

    /** @param quem login de quem excluiu */
    @Transactional
    public void executar(UUID usuarioId, LocalDate data, String quem) {
        RegistroJornada registro = repository.buscarPorData(usuarioId, data)
                .orElseThrow(() -> new RecursoNaoEncontradoException("REGISTRO_NAO_ENCONTRADO",
                        "Não há registro de jornada em %s.".formatted(DATA.format(data))));
        long comprovantes = arquivos.contarPorRegistro(registro.getId());
        if (comprovantes > 0) {
            throw new ConflitoException("REGISTRO_COM_COMPROVANTES",
                    "O dia %s tem %d comprovante(s) PDF arquivado(s) e não pode ser excluído (trilha de auditoria)."
                            .formatted(DATA.format(data), comprovantes));
        }
        trava.exigirAberto(usuarioId, data);
        if (!registro.getBatidas().isVazia()) {
            historico.salvar(AjusteJornada.exclusao(registro, registro.isRegistroManual()
                    ? "Lançamento manual excluído" : "Registro do dia excluído", quem, clock.instant()));
        }
        repository.excluir(registro);
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.EXCLUSAO, null, null));
    }
}
