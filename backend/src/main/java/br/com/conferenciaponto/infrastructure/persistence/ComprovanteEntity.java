package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.TipoBatida;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Mapeamento de tb_comprovante (metadados; o PDF fica no armazenamento de arquivos). */
@Entity
@Table(name = "tb_comprovante")
public class ComprovanteEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "registro_jornada_id", nullable = false, updatable = false)
    private UUID registroJornadaId;

    @Column(name = "caminho_arquivo", nullable = false, length = 500, updatable = false)
    private String caminhoArquivo;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_batida", nullable = false, length = 10)
    private TipoBatida tipoBatida;

    @Column(name = "data_upload", nullable = false, updatable = false)
    private OffsetDateTime dataUpload;

    @Column(name = "data_hora_batida", nullable = false, updatable = false)
    private LocalDateTime dataHoraBatida;

    @Column(name = "nome_original", nullable = false, length = 255, updatable = false)
    private String nomeOriginal;

    @Column(name = "hash_sha256", nullable = false, length = 64, updatable = false)
    private String hashSha256;

    @Column(name = "tamanho_bytes", nullable = false, updatable = false)
    private long tamanhoBytes;

    @Transient
    private boolean novo = true;

    protected ComprovanteEntity() {
        // JPA
    }

    static ComprovanteEntity de(ComprovanteArquivado c) {
        ComprovanteEntity e = new ComprovanteEntity();
        e.id = c.id();
        e.registroJornadaId = c.registroJornadaId();
        e.caminhoArquivo = c.caminhoArquivo();
        e.tipoBatida = c.tipoBatida();
        e.dataUpload = c.dataUpload().atOffset(ZoneOffset.UTC);
        e.dataHoraBatida = c.dataHoraBatida();
        e.nomeOriginal = c.nomeOriginal().length() > 255 ? c.nomeOriginal().substring(0, 255) : c.nomeOriginal();
        e.hashSha256 = c.hashSha256();
        e.tamanhoBytes = c.tamanhoBytes();
        return e;
    }

    ComprovanteArquivado paraDominio() {
        return new ComprovanteArquivado(id, registroJornadaId, caminhoArquivo, tipoBatida, dataUpload.toInstant(),
                dataHoraBatida, nomeOriginal, hashSha256, tamanhoBytes);
    }

    void setTipoBatida(TipoBatida tipoBatida) {
        this.tipoBatida = tipoBatida;
    }

    @PostLoad
    @PostPersist
    void marcarComoPersistido() {
        this.novo = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return novo;
    }
}
