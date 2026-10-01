package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.ArquivoComprovanteView;
import br.com.conferenciaponto.domain.exception.ComprovanteCorrompidoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.UUID;

/** Recupera o PDF arquivado e confere o hash antes de entregá-lo (garantia de integridade). */
@Service
@Transactional(readOnly = true)
public class BaixarComprovanteUseCase {

    private final ComprovanteArquivadoRepository arquivos;
    private final ArmazenamentoComprovantes armazenamento;
    private final RegistroJornadaRepository registros;

    public BaixarComprovanteUseCase(ComprovanteArquivadoRepository arquivos, ArmazenamentoComprovantes armazenamento,
                                    RegistroJornadaRepository registros) {
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.registros = registros;
    }

    /** @param usuarioId titular dos dados consultados: o comprovante precisa ser de um dia dele */
    public ArquivoComprovanteView executar(UUID usuarioId, UUID id) {
        ComprovanteArquivado comprovante = arquivos.buscarPorId(id)
                .filter(c -> registros.buscarPorId(c.registroJornadaId())
                        .map(r -> r.getUsuarioId().equals(usuarioId))
                        .orElse(false))
                .orElseThrow(() -> new RecursoNaoEncontradoException("COMPROVANTE_NAO_ENCONTRADO",
                        "Comprovante %s não encontrado.".formatted(id)));
        byte[] conteudo;
        try {
            conteudo = armazenamento.ler(comprovante.caminhoArquivo());
        } catch (NoSuchFileException e) {
            throw new ComprovanteCorrompidoException("ARQUIVO_AUSENTE",
                    "O arquivo do comprovante não está mais no armazenamento (%s).".formatted(comprovante.caminhoArquivo()));
        } catch (IOException e) {
            throw new ComprovanteCorrompidoException("ARQUIVO_ILEGIVEL",
                    "Falha ao ler o comprovante: " + e.getMessage());
        }
        if (!comprovante.integro(conteudo)) {
            throw new ComprovanteCorrompidoException("COMPROVANTE_CORROMPIDO",
                    "O arquivo armazenado não confere com o hash registrado; o comprovante pode ter sido alterado.");
        }
        return new ArquivoComprovanteView(comprovante, conteudo);
    }
}
