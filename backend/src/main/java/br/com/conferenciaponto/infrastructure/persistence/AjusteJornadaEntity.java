package br.com.conferenciaponto.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Mapeamento JPA de tb_ajuste_jornada (histórico somente-inclusão). */
@Entity
@Table(name = "tb_ajuste_jornada")
public class AjusteJornadaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "registro_jornada_id", updatable = false)
    private UUID registroJornadaId;

    @Column(name = "data_referencia", nullable = false, updatable = false)
    private LocalDate dataReferencia;

    @Column(name = "batidas_antes", nullable = false, updatable = false, length = 60)
    private String batidasAntes;

    @Column(name = "batidas_depois", nullable = false, updatable = false, length = 60)
    private String batidasDepois;

    @Column(name = "justificativa", nullable = false, updatable = false, length = 500)
    private String justificativa;

    @Column(name = "usuario_login", nullable = false, updatable = false, length = 60)
    private String usuarioLogin;

    @Column(name = "ajustado_em", nullable = false, updatable = false)
    private OffsetDateTime ajustadoEm;

    protected AjusteJornadaEntity() {
        // JPA
    }

    AjusteJornadaEntity(UUID id, UUID registroJornadaId, LocalDate dataReferencia, String batidasAntes,
                        String batidasDepois, String justificativa, String usuarioLogin, OffsetDateTime ajustadoEm) {
        this.id = id;
        this.registroJornadaId = registroJornadaId;
        this.dataReferencia = dataReferencia;
        this.batidasAntes = batidasAntes;
        this.batidasDepois = batidasDepois;
        this.justificativa = justificativa;
        this.usuarioLogin = usuarioLogin;
        this.ajustadoEm = ajustadoEm;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Histórico nunca é atualizado: todo save é um INSERT (sem SELECT prévio). */
    @Override
    public boolean isNew() {
        return true;
    }

    public UUID getRegistroJornadaId() {
        return registroJornadaId;
    }

    public LocalDate getDataReferencia() {
        return dataReferencia;
    }

    public String getBatidasAntes() {
        return batidasAntes;
    }

    public String getBatidasDepois() {
        return batidasDepois;
    }

    public String getJustificativa() {
        return justificativa;
    }

    public String getUsuarioLogin() {
        return usuarioLogin;
    }

    public OffsetDateTime getAjustadoEm() {
        return ajustadoEm;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    void setUsuarioId(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }
}
