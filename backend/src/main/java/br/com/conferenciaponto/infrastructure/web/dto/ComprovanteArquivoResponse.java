package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.ComprovanteArquivoView;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.TipoBatida;

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
