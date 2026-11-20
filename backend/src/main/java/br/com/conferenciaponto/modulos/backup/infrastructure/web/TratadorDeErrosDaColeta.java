package br.com.conferenciaponto.modulos.backup.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.backup.domain.ErroDeColeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * As falhas dos backups de clientes.
 * <ul>
 * <li>Página de coleta: {@code {codigo, mensagem}}, com o status do {@link ErroDeColeta} (a página decide por ele se
 * manda o pedaço de novo ou para).</li>
 * <li>Tela do sistema: o envelope do ponto ({@link ApiResponse}), com o mesmo status.</li>
 * </ul>
 */
public final class TratadorDeErrosDaColeta {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErrosDaColeta.class);

    private TratadorDeErrosDaColeta() {
    }

    @RestControllerAdvice(assignableTypes = ColetaPublicaController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class DaPagina {

        @ExceptionHandler(ErroDeColeta.class)
        public ResponseEntity<Map<String, String>> deColeta(ErroDeColeta erro) {
            if (erro.status() >= 500) {
                LOG.warn("{}: {}", erro.codigo(), erro.getMessage());
            }
            return ResponseEntity.status(erro.status()).body(Map.of("codigo", erro.codigo(), "mensagem", erro.getMessage()));
        }

        @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
        public ResponseEntity<Map<String, String>> pedidoInvalido(Exception erro) {
            return ResponseEntity.badRequest().body(Map.of("codigo", "PEDIDO_INVALIDO", "mensagem", "Pedido inválido."));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<Map<String, String>> inesperado(Exception erro) {
            LOG.error("Falha inesperada na página de coleta", erro);
            return ResponseEntity.status(500).body(Map.of("codigo", "FALHA", "mensagem",
                    "Falha inesperada no sistema. Tente de novo; se continuar, veja o log."));
        }
    }

    @RestControllerAdvice(assignableTypes = BackupController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public static class DaTela {

        @ExceptionHandler(ErroDeColeta.class)
        public ResponseEntity<ApiResponse<Void>> deColeta(ErroDeColeta erro) {
            if (erro.status() >= 500) {
                LOG.warn("{}: {}", erro.codigo(), erro.getMessage());
            }
            return ResponseEntity.status(erro.status()).body(ApiResponse.falha(erro.codigo(), erro.getMessage()));
        }
    }
}
