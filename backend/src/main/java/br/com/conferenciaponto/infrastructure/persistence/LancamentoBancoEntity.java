package br.com.conferenciaponto.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tb_lancamento_banco")
public class LancamentoBancoEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "segundos", nullable = false)
    private int segundos;

    @Column(name = "descricao", nullable = false, length = 200)
    private String descricao;

    @Column(name = "criado_em", nullable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "criado_por", length = 60)
    private String criadoPor;

    protected LancamentoBancoEntity() {
        // JPA
    }

    LancamentoBancoEntity(UUID id, LocalDate data, int segundos, String descricao, OffsetDateTime criadoEm,
                          String criadoPor) {
        this.id = id;
        this.data = data;
        this.segundos = segundos;
        this.descricao = descricao;
        this.criadoEm = criadoEm;
        this.criadoPor = criadoPor;
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getData() {
        return data;
    }

    public int getSegundos() {
        return segundos;
    }

    public String getDescricao() {
        return descricao;
    }

    public OffsetDateTime getCriadoEm() {
        return criadoEm;
    }

    public String getCriadoPor() {
        return criadoPor;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    void setUsuarioId(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }
}
