package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.StatusImportacao;
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

@Entity
@Table(name = "tb_comprovante_ponto")
public class ComprovantePontoEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "nome_arquivo", nullable = false, length = 255)
    private String nomeArquivo;

    @Column(name = "hash_sha256", nullable = false, length = 64, unique = true)
    private String hashSha256;

    @Column(name = "data_hora_batida")
    private LocalDateTime dataHoraBatida;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatusImportacao status;

    @Column(name = "mensagem", length = 500)
    private String mensagem;

    @Column(name = "processado_em", nullable = false)
    private OffsetDateTime processadoEm;

    @Transient
    private boolean novo = true;

    protected ComprovantePontoEntity() {
        // JPA
    }

    static ComprovantePontoEntity de(ComprovanteImportado c) {
        ComprovantePontoEntity e = new ComprovantePontoEntity();
        e.id = c.id();
        e.setUsuarioId(c.usuarioId());
        e.nomeArquivo = truncar(c.nomeArquivo(), 255);
        e.hashSha256 = c.hashSha256();
        e.dataHoraBatida = c.dataHoraBatida();
        e.status = c.status();
        e.mensagem = truncar(c.mensagem(), 500);
        e.processadoEm = c.processadoEm().atOffset(ZoneOffset.UTC);
        return e;
    }

    ComprovanteImportado paraDominio() {
        return new ComprovanteImportado(id, getUsuarioId(), nomeArquivo, hashSha256, dataHoraBatida, status, mensagem,
                processadoEm.toInstant());
    }

    private static String truncar(String texto, int limite) {
        return texto == null || texto.length() <= limite ? texto : texto.substring(0, limite);
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

    public UUID getUsuarioId() {
        return usuarioId;
    }

    void setUsuarioId(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }
}
