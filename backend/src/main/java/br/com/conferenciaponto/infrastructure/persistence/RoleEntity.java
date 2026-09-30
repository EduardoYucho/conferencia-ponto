package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Perfil;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** tb_role: perfis fixos, cadastrados pela migração V5. */
@Entity
@Table(name = "tb_role")
public class RoleEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(name = "nome", nullable = false, length = 30, unique = true)
    private Perfil nome;

    @Column(name = "descricao", nullable = false, length = 200)
    private String descricao;

    protected RoleEntity() {
        // JPA
    }

    public Perfil getNome() {
        return nome;
    }
}
