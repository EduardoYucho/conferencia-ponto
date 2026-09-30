package br.com.conferenciaponto.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tb_ciclo_banco")
public class CicloBancoEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "data_inicio", nullable = false)
    private LocalDate dataInicio;

    @Column(name = "data_fim", nullable = false)
    private LocalDate dataFim;

    @Column(name = "data_fim_prevista", nullable = false)
    private LocalDate dataFimPrevista;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "saldo_final_segundos")
    private Integer saldoFinalSegundos;

    @Column(name = "fechado_em")
    private OffsetDateTime fechadoEm;

    @Column(name = "fechado_por", length = 60)
    private String fechadoPor;

    @Column(name = "observacao", length = 200)
    private String observacao;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm;

    protected CicloBancoEntity() {
        // JPA
    }

    CicloBancoEntity(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
    public LocalDate getDataInicio() { return dataInicio; }
    public void setDataInicio(LocalDate v) { this.dataInicio = v; }
    public LocalDate getDataFim() { return dataFim; }
    public void setDataFim(LocalDate v) { this.dataFim = v; }
    public LocalDate getDataFimPrevista() { return dataFimPrevista; }
    public void setDataFimPrevista(LocalDate v) { this.dataFimPrevista = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getSaldoFinalSegundos() { return saldoFinalSegundos; }
    public void setSaldoFinalSegundos(Integer v) { this.saldoFinalSegundos = v; }
    public OffsetDateTime getFechadoEm() { return fechadoEm; }
    public void setFechadoEm(OffsetDateTime v) { this.fechadoEm = v; }
    public String getFechadoPor() { return fechadoPor; }
    public void setFechadoPor(String v) { this.fechadoPor = v; }
    public String getObservacao() { return observacao; }
    public void setObservacao(String v) { this.observacao = v; }
    public OffsetDateTime getCriadoEm() { return criadoEm; }
    public void setCriadoEm(OffsetDateTime v) { this.criadoEm = v; }
}
