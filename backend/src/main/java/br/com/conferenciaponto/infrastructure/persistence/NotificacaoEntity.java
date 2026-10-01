package br.com.conferenciaponto.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tb_notificacao")
public class NotificacaoEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "tipo", nullable = false, length = 30)
    private String tipo;

    @Column(name = "chave", nullable = false, length = 120, updatable = false)
    private String chave;

    @Column(name = "titulo", nullable = false, length = 120)
    private String titulo;

    @Column(name = "mensagem", nullable = false, length = 500)
    private String mensagem;

    @Column(name = "link", length = 200)
    private String link;

    @Column(name = "criada_em", nullable = false, updatable = false)
    private OffsetDateTime criadaEm;

    @Column(name = "lida_em")
    private OffsetDateTime lidaEm;

    protected NotificacaoEntity() {
        // JPA
    }

    NotificacaoEntity(UUID id, String tipo, String chave, String titulo, String mensagem, String link,
                      OffsetDateTime criadaEm, OffsetDateTime lidaEm) {
        this.id = id;
        this.tipo = tipo;
        this.chave = chave;
        this.titulo = titulo;
        this.mensagem = mensagem;
        this.link = link;
        this.criadaEm = criadaEm;
        this.lidaEm = lidaEm;
    }

    public UUID getId() { return id; }
    public String getTipo() { return tipo; }
    public String getChave() { return chave; }
    public String getTitulo() { return titulo; }
    public String getMensagem() { return mensagem; }
    public String getLink() { return link; }
    public OffsetDateTime getCriadaEm() { return criadaEm; }
    public OffsetDateTime getLidaEm() { return lidaEm; }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    void setUsuarioId(UUID usuarioId) {
        this.usuarioId = usuarioId;
    }
}
