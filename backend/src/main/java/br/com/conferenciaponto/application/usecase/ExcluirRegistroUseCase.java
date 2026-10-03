package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Remove o registro de uma data (correção de lançamentos). Dias com comprovante PDF
 * arquivado não podem ser excluídos: o comprovante oficial é a trilha de auditoria.
 */
@Service
public class ExcluirRegistroUseCase {

    private final RegistroJornadaRepository repository;
    private final ComprovanteArquivadoRepository arquivos;
    private final ApplicationEventPublisher eventos;

    public ExcluirRegistroUseCase(RegistroJornadaRepository repository, ComprovanteArquivadoRepository arquivos,
                                  ApplicationEventPublisher eventos) {
        this.repository = repository;
        this.arquivos = arquivos;
        this.eventos = eventos;
    }

    @Transactional
    public void executar(UUID usuarioId, LocalDate data) {
        RegistroJornada registro = repository.buscarPorData(usuarioId, data)
                .orElseThrow(() -> new RecursoNaoEncontradoException("REGISTRO_NAO_ENCONTRADO",
                        "Não há registro de jornada em %s.".formatted(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(data))));
        long comprovantes = arquivos.contarPorRegistro(registro.getId());
        if (comprovantes > 0) {
            throw new ConflitoException("REGISTRO_COM_COMPROVANTES",
                    "O dia %s tem %d comprovante(s) arquivado(s) e não pode ser apagado: os comprovantes são a prova das batidas."
                            .formatted(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(data), comprovantes));
        }
        repository.excluir(registro);
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.EXCLUSAO, null, null));
    }
}
