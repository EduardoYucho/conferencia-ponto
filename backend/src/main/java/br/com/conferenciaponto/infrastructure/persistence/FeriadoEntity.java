package br.com.conferenciaponto.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "tb_feriado")
public class FeriadoEntity {

    @Id
    @Column(name = "data", nullable = false)
    private LocalDate data;

    @Column(name = "descricao", nullable = false, length = 120)
    private String descricao;

    @Column(name = "abrangencia", nullable = false, length = 20)
    private String abrangencia;

    protected FeriadoEntity() {
        // JPA
    }

    FeriadoEntity(LocalDate data, String descricao, String abrangencia) {
        this.data = data;
        this.descricao = descricao;
        this.abrangencia = abrangencia;
    }

    public LocalDate getData() {
        return data;
    }

    public String getDescricao() {
        return descricao;
    }

    public String getAbrangencia() {
        return abrangencia;
    }
}
