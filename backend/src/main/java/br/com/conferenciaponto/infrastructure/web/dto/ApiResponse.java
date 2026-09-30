package br.com.conferenciaponto.infrastructure.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Envelope padrão de TODAS as respostas da API.
 *
 * <pre>
 * { "sucesso": true,  "dados": {...}, "erros": [],        "timestamp": "..." }
 * { "sucesso": false, "dados": null,  "erros": [{...}],   "timestamp": "..." }
 * </pre>
 */
public record ApiResponse<T>(boolean sucesso, T dados, List<ApiErro> erros, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T dados) {
        return new ApiResponse<>(true, dados, List.of(), Instant.now());
    }

    public static ApiResponse<Void> falha(List<ApiErro> erros) {
        return new ApiResponse<>(false, null, List.copyOf(erros), Instant.now());
    }

    public static ApiResponse<Void> falha(String codigo, String mensagem) {
        return falha(List.of(new ApiErro(codigo, mensagem, null)));
    }
}
