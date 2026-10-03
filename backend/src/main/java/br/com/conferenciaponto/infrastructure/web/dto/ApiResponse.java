package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.infrastructure.log.ContextoDeLog;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Envelope padrão de TODAS as respostas da API.
 *
 * <pre>
 * { "sucesso": true,  "dados": {...}, "erros": [],        "timestamp": "..." }
 * { "sucesso": false, "dados": null,  "erros": [{...}],   "timestamp": "...", "protocolo": "K7F3QX" }
 * </pre>
 *
 * @param protocolo só nas falhas: o código da requisição, que também está em cada linha do log dela (é o que
 *                  a pessoa informa ao administrador quando o erro é inesperado)
 */
public record ApiResponse<T>(boolean sucesso, T dados, List<ApiErro> erros, Instant timestamp,
                             @JsonInclude(JsonInclude.Include.NON_NULL) String protocolo) {

    public static <T> ApiResponse<T> ok(T dados) {
        return new ApiResponse<>(true, dados, List.of(), Instant.now(), null);
    }

    public static ApiResponse<Void> falha(List<ApiErro> erros) {
        return new ApiResponse<>(false, null, List.copyOf(erros), Instant.now(), ContextoDeLog.protocolo());
    }

    public static ApiResponse<Void> falha(String codigo, String mensagem) {
        return falha(List.of(new ApiErro(codigo, mensagem, null)));
    }
}
