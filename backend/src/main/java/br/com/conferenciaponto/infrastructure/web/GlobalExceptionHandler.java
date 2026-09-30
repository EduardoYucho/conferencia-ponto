package br.com.conferenciaponto.infrastructure.web;

import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import br.com.conferenciaponto.domain.exception.ComprovanteCorrompidoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.infrastructure.web.dto.ApiErro;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.DateTimeException;
import java.util.List;

/** Converte exceções no envelope {@link ApiResponse} com o status HTTP adequado. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RegraNegocioException.class)
    public ResponseEntity<ApiResponse<Void>> regraNegocio(RegraNegocioException ex) {
        return resposta(HttpStatus.UNPROCESSABLE_ENTITY, ex);
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ApiResponse<Void>> naoEncontrado(RecursoNaoEncontradoException ex) {
        return resposta(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(CredenciaisInvalidasException.class)
    public ResponseEntity<ApiResponse<Void>> credenciaisInvalidas(CredenciaisInvalidasException ex) {
        return resposta(HttpStatus.UNAUTHORIZED, ex);
    }

    @ExceptionHandler(ComprovanteCorrompidoException.class)
    public ResponseEntity<ApiResponse<Void>> comprovanteCorrompido(ComprovanteCorrompidoException ex) {
        log.error("Integridade do comprovante: {}", ex.getMessage());
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    @ExceptionHandler(ConflitoException.class)
    public ResponseEntity<ApiResponse<Void>> conflito(ConflitoException ex) {
        return resposta(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validacao(MethodArgumentNotValidException ex) {
        List<ApiErro> erros = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErro("VALIDACAO", fe.getDefaultMessage(), fe.getField()))
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.falha(erros));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> corpoInvalido(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.falha("CORPO_INVALIDO",
                "Corpo da requisição inválido. Use datas yyyy-MM-dd e horários HH:mm ou HH:mm:ss."));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            DateTimeException.class})
    public ResponseEntity<ApiResponse<Void>> parametroInvalido(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.falha("PARAMETRO_INVALIDO",
                "Parâmetro inválido: " + ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> arquivoGrande(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.falha("ARQUIVO_GRANDE_DEMAIS",
                "O arquivo passa do limite de 10 MB."));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ApiResponse<Void>> arquivoAusente(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.falha("ARQUIVO_OBRIGATORIO",
                "Envie o PDF no campo \"arquivo\" (multipart/form-data)."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> integridade(DataIntegrityViolationException ex) {
        log.warn("Violação de integridade: {}", ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.falha("INTEGRIDADE",
                "O registro conflita com dados existentes. Recarregue e tente novamente."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> rotaInexistente(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.falha("ROTA_INEXISTENTE",
                "Recurso não encontrado: /" + ex.getResourcePath()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> metodoNaoSuportado(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.falha("METODO_NAO_SUPORTADO",
                ex.getMessage()));
    }

    /** Cliente SSE fechou a conexão: não há para quem responder. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void clienteDesconectado(AsyncRequestNotUsableException ex) {
        log.debug("Conexão assíncrona encerrada pelo cliente: {}", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> erroInesperado(Exception ex, HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            // stream SSE já iniciado: não é possível devolver JSON; o EventSource reconecta sozinho
            log.debug("Falha em stream SSE: {}", ex.getMessage());
            return null;
        }
        log.error("Erro inesperado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.falha("ERRO_INTERNO",
                "Erro inesperado. Consulte os logs do servidor."));
    }

    private static ResponseEntity<ApiResponse<Void>> resposta(HttpStatus status, DominioException ex) {
        return ResponseEntity.status(status).body(ApiResponse.falha(ex.getCodigo(), ex.getMessage()));
    }
}
