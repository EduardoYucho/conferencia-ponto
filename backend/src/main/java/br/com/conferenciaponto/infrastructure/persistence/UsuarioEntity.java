package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Entity
@Table(name = "tb_usuario")
public class UsuarioEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "login", nullable = false, length = 60, unique = true, updatable = false)
    private String login;

    @Column(name = "nome", nullable = false, length = 120)
    private String nome;

    @Column(name = "senha_hash", nullable = false, length = 100)
    private String senhaHash;

    @Column(name = "ativo", nullable = false)
    private boolean ativo;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "ultimo_login_em")
    private OffsetDateTime ultimoLoginEm;

    @Column(name = "pasta_comprovantes", length = 500)
    private String pastaComprovantes;

    @Column(name = "trocar_senha", nullable = false)
    private boolean trocarSenha;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "tb_usuario_role",
            joinColumns = @JoinColumn(name = "usuario_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<RoleEntity> roles = new HashSet<>();

    @Transient
    private boolean novo = true;

    protected UsuarioEntity() {
        // JPA
    }

    UsuarioEntity(UUID id, String login) {
        this.id = id;
        this.login = login;
    }

    void copiar(Usuario usuario, Set<RoleEntity> rolesDoUsuario) {
        this.nome = usuario.nome();
        this.senhaHash = usuario.senhaHash();
        this.ativo = usuario.ativo();
        this.ultimoLoginEm = usuario.ultimoLoginEm() == null ? null : usuario.ultimoLoginEm().atOffset(ZoneOffset.UTC);
        this.pastaComprovantes = usuario.pastaComprovantes();
        this.trocarSenha = usuario.trocarSenha();
        this.roles.clear();
        this.roles.addAll(rolesDoUsuario);
    }

    Usuario paraDominio() {
        return new Usuario(id, login, nome, senhaHash, ativo,
                roles.stream().map(RoleEntity::getNome).collect(Collectors.toSet()),
                ultimoLoginEm == null ? null : ultimoLoginEm.toInstant(), pastaComprovantes, trocarSenha);
    }

    @PrePersist
    void aoInserir() {
        this.criadoEm = OffsetDateTime.now();
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
