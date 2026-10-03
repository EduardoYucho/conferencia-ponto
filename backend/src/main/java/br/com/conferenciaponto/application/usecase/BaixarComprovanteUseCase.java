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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BaixarComprovanteUseCase.class);

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
                        "Este comprovante não foi encontrado. Recarregue a tela e tente de novo."));
        byte[] conteudo;
        try {
            conteudo = armazenamento.ler(comprovante.caminhoArquivo());
        } catch (NoSuchFileException e) {
            log.error("Comprovante {} sem o arquivo no armazenamento: {}", id, comprovante.caminhoArquivo());
            throw new ComprovanteCorrompidoException("ARQUIVO_AUSENTE",
                    "O PDF deste comprovante não está mais guardado no sistema. Avise o administrador.");
        } catch (IOException e) {
            log.error("Comprovante {} ilegível em {}", id, comprovante.caminhoArquivo(), e);
            throw new ComprovanteCorrompidoException("ARQUIVO_ILEGIVEL",
                    "Não foi possível abrir o PDF deste comprovante agora. Tente de novo; se continuar, avise o administrador.");
        }
        if (!comprovante.integro(conteudo)) {
            log.error("Comprovante {} não confere com o hash registrado: {}", id, comprovante.caminhoArquivo());
            throw new ComprovanteCorrompidoException("COMPROVANTE_CORROMPIDO",
                    "O PDF guardado deste comprovante foi alterado depois de arquivado e não é confiável. Avise o administrador.");
        }
        return new ArquivoComprovanteView(comprovante, conteudo);
    }
}
