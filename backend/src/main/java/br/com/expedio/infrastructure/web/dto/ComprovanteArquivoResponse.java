package br.com.expedio.infrastructure.web.dto;

import br.com.expedio.application.view.ComprovanteArquivoView;
import br.com.expedio.domain.model.ComprovanteArquivado;
import br.com.expedio.domain.model.TipoBatida;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record ComprovanteArquivoResponse(
        UUID id,
        TipoBatida tipoBatida,
        String rotulo,
        LocalDateTime dataHoraBatida,
        Instant dataUpload,
        String nomeOriginal,
        long tamanhoBytes,
        String hashSha256,
        String urlDownload) {

    public static ComprovanteArquivoResponse de(ComprovanteArquivoView v) {
        ComprovanteArquivado c = v.comprovante();
        return new ComprovanteArquivoResponse(c.id(), c.tipoBatida(), c.tipoBatida().rotulo(), c.dataHoraBatida(),
                c.dataUpload(), c.nomeOriginal(), c.tamanhoBytes(), c.hashSha256(), v.uriDownload().toString());
    }
}
