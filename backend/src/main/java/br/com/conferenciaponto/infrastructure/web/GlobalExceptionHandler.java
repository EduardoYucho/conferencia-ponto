package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ComprovanteCorrompidoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.infrastructure.log.ContextoDeLog;
import br.com.conferenciaponto.infrastructure.web.dto.ApiErro;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Converte toda exceção no envelope {@link ApiResponse}, com uma mensagem que a pessoa entende e consegue
 * resolver. Nada técnico (texto de exceção, SQL, nome de classe) vai para a tela: o detalhe fica no log, na
 * linha que tem o mesmo protocolo devolvido na resposta.
 *
 * <ul>
 *   <li>recusas previsíveis (regra de negócio, validação, permissão): mensagem específica, registrada como INFO;</li>
 *   <li>problemas do ambiente (banco fora do ar, sistema ocupado): diz para tentar de novo, registrado como WARN;</li>
 *   <li>o que não era esperado: mensagem genérica com o protocolo, registrado como ERROR com a pilha.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ domínio

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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.falha(ex.getCodigo(), ex.getMessage()));
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<ApiResponse<Void>> acessoNegado(AcessoNegadoException ex) {
        return resposta(HttpStatus.FORBIDDEN, ex);
    }

    @ExceptionHandler(ConflitoException.class)
    public ResponseEntity<ApiResponse<Void>> conflito(ConflitoException ex) {
        return resposta(HttpStatus.CONFLICT, ex);
    }

    // ------------------------------------------------------------------ dados enviados

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validacao(MethodArgumentNotValidException ex) {
        List<ApiErro> erros = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErro("VALIDACAO", fe.getDefaultMessage(), fe.getField()))
                .toList();
        if (erros.isEmpty()) {
            erros = List.of(new ApiErro("VALIDACAO", "Confira os dados informados.", null));
        }
        log.info("Recusado (validação): {}", erros.stream().map(e -> e.campo() + ": " + e.mensagem()).collect(Collectors.joining("; ")));
        return ResponseEntity.badRequest().body(ApiResponse.falha(erros));
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> validacaoDeParametro(Exception ex) {
        log.info("Recusado (validação de parâmetro): {}", ex.getMessage());
        return recusa(HttpStatus.BAD_REQUEST, "VALIDACAO", "Confira os dados informados: algum valor não é aceito.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> corpoInvalido(HttpMessageNotReadableException ex) {
        ApiErro erro = erroDoCorpo(ex);
        log.info("Recusado (dados enviados): {} [{}]", erro.mensagem(), causaResumida(ex));
        return ResponseEntity.badRequest().body(ApiResponse.falha(List.of(erro)));
    }

    /** Diz qual campo veio errado e o que é aceito, em vez de uma mensagem única para tudo. */
    private static ApiErro erroDoCorpo(HttpMessageNotReadableException ex) {
        Throwable causa = ex.getCause();
        if (causa instanceof InvalidFormatException formato) {
            String campo = caminho(formato);
            Class<?> tipo = formato.getTargetType();
            String valor = String.valueOf(formato.getValue());
            if (tipo != null && tipo.isEnum()) {
                return new ApiErro("CORPO_INVALIDO", "Valor \"%s\" não é aceito%s. Use: %s.".formatted(valor,
                        campo == null ? "" : " em \"" + campo + "\"",
                        Arrays.stream(tipo.getEnumConstants()).map(String::valueOf).collect(Collectors.joining(", "))), campo);
            }
            return new ApiErro("CORPO_INVALIDO", "Valor \"%s\" inválido%s%s".formatted(valor,
                    campo == null ? "" : " em \"" + campo + "\"", dicaDoTipo(tipo)), campo);
        }
        if (causa instanceof MismatchedInputException tipoErrado) {
            String campo = caminho(tipoErrado);
            if (campo == null) {
                return new ApiErro("CORPO_INVALIDO", "Faltou enviar os dados desta operação.", null);
            }
            return new ApiErro("CORPO_INVALIDO", "O campo \"%s\" veio vazio ou num formato que o sistema não aceita%s"
                    .formatted(campo, dicaDoTipo(tipoErrado.getTargetType())), campo);
        }
        if (causa instanceof JsonParseException) {
            return new ApiErro("CORPO_INVALIDO", "Os dados enviados estão incompletos ou mal formados. Recarregue a "
                    + "tela e tente de novo.", null);
        }
        if (causa instanceof JsonMappingException mapeamento && mapeamento.getCause() instanceof DominioException dominio) {
            // regra conferida ao montar o objeto (ex.: horário inválido)
            return new ApiErro(dominio.getCodigo(), dominio.getMessage(), caminho(mapeamento));
        }
        return new ApiErro("CORPO_INVALIDO", "Faltou enviar os dados desta operação, ou eles vieram num formato que o "
                + "sistema não aceita.", null);
    }

    private static String caminho(JsonMappingException ex) {
        String caminho = ex.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining("."))
                .replace(".[", "[");
        return caminho.isBlank() ? null : caminho;
    }

    private static String dicaDoTipo(Class<?> tipo) {
        if (tipo == null) {
            return ".";
        }
        if (LocalDate.class.isAssignableFrom(tipo)) {
            return ": a data precisa estar no formato aaaa-mm-dd (ex.: 2026-09-28).";
        }
        if (LocalTime.class.isAssignableFrom(tipo)) {
            return ": o horário precisa estar no formato hh:mm ou hh:mm:ss (ex.: 08:05).";
        }
        if (Temporal.class.isAssignableFrom(tipo)) {
            return ": data ou horário fora do formato esperado.";
        }
        if (Number.class.isAssignableFrom(tipo) || tipo.isPrimitive() && tipo != boolean.class) {
            return ": informe um número.";
        }
        if (tipo == Boolean.class || tipo == boolean.class) {
            return ": informe verdadeiro ou falso.";
        }
        if (UUID.class.isAssignableFrom(tipo)) {
            return ": identificador inválido.";
        }
        return ".";
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> parametroDeTipoErrado(MethodArgumentTypeMismatchException ex) {
        String nome = ex.getName();
        Class<?> tipo = ex.getRequiredType();
        String mensagem;
        if (tipo != null && tipo.isEnum()) {
            mensagem = "Valor \"%s\" não é aceito em \"%s\". Use: %s.".formatted(ex.getValue(), nome,
                    Arrays.stream(tipo.getEnumConstants()).map(String::valueOf).collect(Collectors.joining(", ")));
        } else if (tipo != null && UUID.class.isAssignableFrom(tipo)) {
            mensagem = "Este registro não foi encontrado (identificador inválido). Recarregue a tela e tente de novo.";
        } else {
            mensagem = "Valor \"%s\" inválido em \"%s\"%s".formatted(ex.getValue(), nome, dicaDoTipo(tipo));
        }
        log.info("Recusado (parâmetro): {}", mensagem);
        return ResponseEntity.badRequest().body(ApiResponse.falha(List.of(new ApiErro("PARAMETRO_INVALIDO", mensagem, nome))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> parametroAusente(MissingServletRequestParameterException ex) {
        return recusa(HttpStatus.BAD_REQUEST, "PARAMETRO_INVALIDO", "Faltou informar \"%s\".".formatted(ex.getParameterName()));
    }

    // ------------------------------------------------------------------ arquivos

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> arquivoGrande(MaxUploadSizeExceededException ex) {
        return recusa(HttpStatus.PAYLOAD_TOO_LARGE, "ARQUIVO_GRANDE_DEMAIS",
                "O envio é grande demais: cada arquivo pode ter até 10 MB. Envie menos arquivos de cada vez ou um arquivo menor.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> arquivoAusente(MissingServletRequestPartException ex) {
        return recusa(HttpStatus.BAD_REQUEST, "ARQUIVO_OBRIGATORIO", "Escolha o arquivo PDF antes de enviar.");
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> envioDeArquivoFalhou(MultipartException ex) {
        log.warn("Envio de arquivo interrompido: {}", causaResumida(ex));
        return recusa(HttpStatus.BAD_REQUEST, "ARQUIVO_OBRIGATORIO",
                "O envio do arquivo não chegou inteiro. Escolha o arquivo PDF e tente de novo.");
    }

    // ------------------------------------------------------------------ banco de dados e ambiente

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> integridade(DataIntegrityViolationException ex) {
        String estado = estadoSql(ex);
        if ("23505".equals(estado) || "23P01".equals(estado)) { // chave repetida ou períodos sobrepostos
            log.info("Recusado (registro repetido): {}", ex.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.falha("REGISTRO_REPETIDO",
                    "Já existe um registro igual a este. Recarregue a tela para ver os dados atuais."));
        }
        if ("23503".equals(estado)) { // ainda é usado por outro registro
            log.info("Recusado (registro em uso): {}", ex.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.falha("REGISTRO_EM_USO",
                    "Este registro está ligado a outros dados e não pode ser removido ou alterado assim."));
        }
        if ("22001".equals(estado)) { // texto maior do que a coluna
            log.warn("Texto maior do que o banco aceita: {}", ex.getMostSpecificCause().getMessage());
            return recusa(HttpStatus.UNPROCESSABLE_ENTITY, "TEXTO_LONGO_DEMAIS",
                    "Um dos textos informados é longo demais. Encurte e tente de novo.");
        }
        return inesperado(ex, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> alteradoPorOutro(OptimisticLockingFailureException ex) {
        log.info("Recusado (registro já alterado ou excluído): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.falha("REGISTRO_JA_ALTERADO",
                "Esse registro já foi alterado ou excluído em outra tela. Recarregue para ver os dados atuais."));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            CannotGetJdbcConnectionException.class, QueryTimeoutException.class, TransientDataAccessException.class})
    public ResponseEntity<ApiResponse<Void>> bancoIndisponivel(Exception ex, HttpServletRequest requisicao) {
        log.error("Banco de dados indisponível em {} {}: {}", requisicao.getMethod(), requisicao.getRequestURI(), causaResumida(ex));
        if (esperaEventos(requisicao)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.falha("BANCO_INDISPONIVEL",
                "O banco de dados do sistema não respondeu. Tente de novo em instantes; se continuar, avise o administrador."));
    }

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> sistemaOcupado(TaskRejectedException ex) {
        log.warn("Tarefa recusada (fila cheia): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.falha("SISTEMA_OCUPADO",
                "O sistema já está fazendo essa tarefa. Aguarde terminar e tente de novo."));
    }

    // ------------------------------------------------------------------ endereços e formatos

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> rotaInexistente(Exception ex, HttpServletRequest requisicao) {
        log.info("Endereço inexistente: {} {}", requisicao.getMethod(), requisicao.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.falha("ROTA_INEXISTENTE",
                "Este endereço não existe no sistema. Se você chegou aqui por um botão, recarregue a tela (o sistema "
                        + "pode ter sido atualizado)."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> metodoNaoSuportado(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest requisicao) {
        log.info("Operação inexistente: {} {}", requisicao.getMethod(), requisicao.getRequestURI());
        String caminho = requisicao.getRequestURI().substring(requisicao.getContextPath().length());
        boolean semRota = ex.getSupportedHttpMethods() == null || caminho.startsWith("/api/")
                && ex.getSupportedHttpMethods().stream().allMatch(m -> m.name().equals("GET") || m.name().equals("HEAD"))
                && !requisicao.getMethod().equals("GET");
        return ResponseEntity.status(semRota ? HttpStatus.NOT_FOUND : HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.falha(semRota ? "ROTA_INEXISTENTE" : "METODO_NAO_SUPORTADO",
                        "Esta operação não existe no sistema. Recarregue a tela (o sistema pode ter sido atualizado)."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> formatoDeEnvio(HttpMediaTypeNotSupportedException ex) {
        return recusa(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FORMATO_NAO_SUPORTADO",
                "Os dados foram enviados num formato que esta operação não aceita. Recarregue a tela e tente de novo.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> formatoDeResposta(HttpMediaTypeNotAcceptableException ex, HttpServletRequest requisicao) {
        log.info("Formato de resposta não disponível em {} (Accept: {})", requisicao.getRequestURI(),
                requisicao.getHeader(HttpHeaders.ACCEPT));
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    // ------------------------------------------------------------------ conexões encerradas

    /** Cliente fechou a aba ou perdeu a rede no meio da resposta: não há para quem responder. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public void clienteDesconectado(Exception ex) {
        log.debug("Conexão encerrada pelo cliente: {}", ex.getMessage());
    }

    // ------------------------------------------------------------------ o que não era esperado

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> erroInesperado(Exception ex, HttpServletRequest requisicao) {
        if (ex instanceof IOException && conexaoEncerrada(ex)) {
            log.debug("Conexão encerrada pelo cliente: {}", ex.getMessage());
            return null;
        }
        return inesperado(ex, requisicao);
    }

    private ResponseEntity<ApiResponse<Void>> inesperado(Exception ex, HttpServletRequest requisicao) {
        String onde = requisicao == null ? "" : " em %s %s".formatted(requisicao.getMethod(), requisicao.getRequestURI());
        log.error("Erro inesperado{} (usuário {})", onde, ContextoDeLog.usuario(), ex);
        if (requisicao != null && esperaEventos(requisicao)) {
            // stream de eventos: não dá para devolver JSON; o navegador reconecta sozinho
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        String protocolo = ContextoDeLog.protocolo();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.falha("ERRO_INTERNO",
                "Aconteceu um erro inesperado no sistema e a operação não foi concluída. Tente de novo; se continuar, "
                        + "informe ao administrador" + (protocolo == null ? "." : " o protocolo " + protocolo + ".")));
    }

    // ------------------------------------------------------------------ apoio

    /** Recusa previsível: a mensagem da exceção de domínio já é a que a pessoa lê. */
    private static ResponseEntity<ApiResponse<Void>> resposta(HttpStatus status, DominioException ex) {
        log.info("Recusado ({}): {}", ex.getCodigo(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.falha(ex.getCodigo(), ex.getMessage()));
    }

    private static ResponseEntity<ApiResponse<Void>> recusa(HttpStatus status, String codigo, String mensagem) {
        log.info("Recusado ({}): {}", codigo, mensagem);
        return ResponseEntity.status(status).body(ApiResponse.falha(codigo, mensagem));
    }

    private static boolean esperaEventos(HttpServletRequest requisicao) {
        String accept = requisicao.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private static boolean conexaoEncerrada(Throwable ex) {
        String mensagem = String.valueOf(ex.getMessage()).toLowerCase();
        return mensagem.contains("broken pipe") || mensagem.contains("connection reset")
                || mensagem.contains("conexão") && mensagem.contains("anulada")
                || ex.getClass().getSimpleName().equals("ClientAbortException");
    }

    private static String estadoSql(Throwable ex) {
        for (Throwable causa = ex; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String causaResumida(Throwable ex) {
        Throwable raiz = ex;
        while (raiz.getCause() != null && raiz.getCause() != raiz) {
            raiz = raiz.getCause();
        }
        String mensagem = String.valueOf(raiz.getMessage()).replaceAll("\\s+", " ");
        return raiz.getClass().getSimpleName() + ": " + (mensagem.length() > 300 ? mensagem.substring(0, 300) + "…" : mensagem);
    }
}
