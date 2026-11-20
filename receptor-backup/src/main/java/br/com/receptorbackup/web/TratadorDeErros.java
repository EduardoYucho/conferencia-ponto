package br.com.receptorbackup.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import br.com.receptorbackup.coleta.ErroDeColeta;

/** Toda falha volta como {@code {codigo, mensagem}}, com uma frase que dá para mostrar na tela. */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger LOG = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(ErroDeColeta.class)
    public ResponseEntity<Map<String, String>> deColeta(ErroDeColeta erro) {
        if (erro.status() >= 500) {
            LOG.warn("{}: {}", erro.codigo(), erro.getMessage());
        }
        return ResponseEntity.status(erro.status()).body(Map.of("codigo", erro.codigo(), "mensagem", erro.getMessage()));
    }

    @ExceptionHandler({ HttpMessageNotReadableException.class, MissingServletRequestParameterException.class })
    public ResponseEntity<Map<String, String>> pedidoInvalido(Exception erro) {
        return ResponseEntity.badRequest().body(Map.of("codigo", "PEDIDO_INVALIDO", "mensagem", "Pedido inválido."));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> naoEncontrado(Exception erro) {
        return ResponseEntity.status(404).body(Map.of("codigo", "NAO_ENCONTRADO", "mensagem", "Não encontrado."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> inesperado(Exception erro) {
        LOG.error("Falha inesperada", erro);
        return ResponseEntity.status(500).body(Map.of("codigo", "FALHA", "mensagem",
                "Falha inesperada no Receptor. Tente de novo; se continuar, veja o log."));
    }
}
