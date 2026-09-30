package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * PDF de comprovante guardado no armazenamento da aplicação e vinculado ao dia
 * (tb_comprovante). O binário fica fora do banco, em {@code caminhoArquivo}.
 *
 * @param tipoBatida posição da batida que o comprovante prova; pode mudar se um PDF
 *                   anterior chegar depois (as colunas são reorganizadas em ordem cronológica)
 */
public record ComprovanteArquivado(
        UUID id,
        UUID registroJornadaId,
        String caminhoArquivo,
        TipoBatida tipoBatida,
        Instant dataUpload,
        LocalDateTime dataHoraBatida,
        String nomeOriginal,
        String hashSha256,
        long tamanhoBytes) {

    private static final DateTimeFormatter HORA_ARQUIVO = DateTimeFormatter.ofPattern("HHmmss");

    public ComprovanteArquivado comTipo(TipoBatida novoTipo) {
        return new ComprovanteArquivado(id, registroJornadaId, caminhoArquivo, novoTipo, dataUpload,
                dataHoraBatida, nomeOriginal, hashSha256, tamanhoBytes);
    }

    /** O conteúdo lido do armazenamento é idêntico ao arquivado? */
    public boolean integro(byte[] conteudo) {
        return conteudo != null && conteudo.length == tamanhoBytes && HashSha256.de(conteudo).equals(hashSha256);
    }

    /** Nome amigável para download, ex.: comprovante_2026-09-28_ENTRADA_1_080231.pdf */
    public String nomeDownload() {
        return "comprovante_%s_%s_%s.pdf".formatted(
                dataHoraBatida.toLocalDate(), tipoBatida, HORA_ARQUIVO.format(dataHoraBatida));
    }
}
