package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Planilha do Google Sheets em que o sistema mantém a conferência de ponto de um usuário.
 *
 * @param planilhaId      identificador da planilha (o trecho do link depois de {@code /d/})
 * @param titulo          nome da planilha no Google, na última vez em que foi lida
 * @param sincronizadaEm  última gravação bem-sucedida ({@code null} = ainda não gravou)
 * @param erro            por que a última tentativa falhou ({@code null} = a última gravação deu certo)
 */
public record VinculoPlanilha(UUID usuarioId, String planilhaId, String titulo, Instant vinculadaEm,
                              String vinculadaPor, Instant sincronizadaEm, String erro) {

    public static final int TAMANHO_ERRO = 600;
    public static final int TAMANHO_TITULO = 300;

    private static final Pattern LINK = Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{20,})");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,}");

    public VinculoPlanilha {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(planilhaId, "planilhaId");
        Objects.requireNonNull(vinculadaEm, "vinculadaEm");
        titulo = titulo == null || titulo.isBlank() ? null : titulo.strip();
        if (titulo != null && titulo.length() > TAMANHO_TITULO) {
            titulo = titulo.substring(0, TAMANHO_TITULO);
        }
        erro = erro == null || erro.isBlank() ? null
                : erro.length() > TAMANHO_ERRO ? erro.substring(0, TAMANHO_ERRO) : erro;
    }

    public static VinculoPlanilha novo(UUID usuarioId, String planilhaId, String titulo, String quem, Instant agora) {
        return new VinculoPlanilha(usuarioId, planilhaId, titulo, agora, quem, null, null);
    }

    /**
     * Extrai o identificador do link colado da barra de endereços
     * ({@code https://docs.google.com/spreadsheets/d/<id>/edit...}); aceita também só o identificador.
     */
    public static String idDoLink(String link) {
        String texto = link == null ? "" : link.strip();
        Matcher m = LINK.matcher(texto);
        if (m.find()) {
            return m.group(1);
        }
        if (ID.matcher(texto).matches()) {
            return texto;
        }
        throw new RegraNegocioException("PLANILHA_LINK_INVALIDO",
                "Cole o link da planilha do Google (https://docs.google.com/spreadsheets/d/...).");
    }

    public String url() {
        return "https://docs.google.com/spreadsheets/d/" + planilhaId + "/edit";
    }

    public VinculoPlanilha sincronizada(String tituloAtual, Instant quando) {
        return new VinculoPlanilha(usuarioId, planilhaId, tituloAtual == null ? titulo : tituloAtual, vinculadaEm,
                vinculadaPor, quando, null);
    }

    public VinculoPlanilha comErro(String mensagem) {
        return new VinculoPlanilha(usuarioId, planilhaId, titulo, vinculadaEm, vinculadaPor, sincronizadaEm,
                mensagem == null || mensagem.isBlank() ? "Falha ao gravar a planilha." : mensagem);
    }
}
