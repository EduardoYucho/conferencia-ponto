package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.TipoDia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mapeamento JPA de tb_registro_jornada. Não contém regra de negócio:
 * a conversão para o agregado de domínio fica em {@link RegistroJornadaMapper}.
 *
 * <p>Implementa {@link Persistable} porque o UUID é gerado pelo domínio;
 * sem isso o Spring Data faria merge (SELECT extra) em todo INSERT.
 */
@Entity
@Table(name = "tb_registro_jornada")
public class RegistroJornadaEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario_id", nullable = false, updatable = false)
    private UUID usuarioId;

    @Column(name = "data_referencia", nullable = false, updatable = false)
    private LocalDate dataReferencia;

    @Column(name = "entrada_1")
    private LocalTime entrada1;

    @Column(name = "saida_1")
    private LocalTime saida1;

    @Column(name = "entrada_2")
    private LocalTime entrada2;

    @Column(name = "saida_2")
    private LocalTime saida2;

    @Column(name = "entrada_3")
    private LocalTime entrada3;

    @Column(name = "saida_3")
    private LocalTime saida3;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_dia", nullable = false, length = 20)
    private TipoDia tipoDia;

    @Column(name = "jornada_prevista_segundos", nullable = false)
    private int jornadaPrevistaSegundos;

    @Column(name = "segundos_trabalhados", nullable = false)
    private int segundosTrabalhados;

    @Column(name = "saldo_diario_segundos")
    private Integer saldoDiarioSegundos;

    @Column(name = "registro_manual", nullable = false)
    private boolean registroManual;

    @Column(name = "horarios_ajustados", length = 60)
    private String horariosAjustados;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private OffsetDateTime criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private OffsetDateTime atualizadoEm;

    @Transient
    private boolean novo = true;

    protected RegistroJornadaEntity() {
        // JPA
    }

    RegistroJornadaEntity(UUID id, UUID usuarioId, LocalDate dataReferencia) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.dataReferencia = dataReferencia;
    }

    @PrePersist
    void aoInserir() {
        OffsetDateTime agora = OffsetDateTime.now();
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    @PreUpdate
    void aoAtualizar() {
        this.atualizadoEm = OffsetDateTime.now();
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

    public LocalDate getDataReferencia() {
        return dataReferencia;
    }

    public LocalTime getEntrada1() {
        return entrada1;
    }

    public void setEntrada1(LocalTime entrada1) {
        this.entrada1 = entrada1;
    }

    public LocalTime getSaida1() {
        return saida1;
    }

    public void setSaida1(LocalTime saida1) {
        this.saida1 = saida1;
    }

    public LocalTime getEntrada2() {
        return entrada2;
    }

    public void setEntrada2(LocalTime entrada2) {
        this.entrada2 = entrada2;
    }

    public LocalTime getSaida2() {
        return saida2;
    }

    public void setSaida2(LocalTime saida2) {
        this.saida2 = saida2;
    }

    public TipoDia getTipoDia() {
        return tipoDia;
    }

    public void setTipoDia(TipoDia tipoDia) {
        this.tipoDia = tipoDia;
    }

    public int getJornadaPrevistaSegundos() {
        return jornadaPrevistaSegundos;
    }

    public void setJornadaPrevistaSegundos(int jornadaPrevistaSegundos) {
        this.jornadaPrevistaSegundos = jornadaPrevistaSegundos;
    }

    public int getSegundosTrabalhados() {
        return segundosTrabalhados;
    }

    public void setSegundosTrabalhados(int segundosTrabalhados) {
        this.segundosTrabalhados = segundosTrabalhados;
    }

    public Integer getSaldoDiarioSegundos() {
        return saldoDiarioSegundos;
    }

    public void setSaldoDiarioSegundos(Integer saldoDiarioSegundos) {
        this.saldoDiarioSegundos = saldoDiarioSegundos;
    }

    public boolean isRegistroManual() {
        return registroManual;
    }

    public void setRegistroManual(boolean registroManual) {
        this.registroManual = registroManual;
    }

    public OffsetDateTime getCriadoEm() {
        return criadoEm;
    }

    public OffsetDateTime getAtualizadoEm() {
        return atualizadoEm;
    }

    public String getHorariosAjustados() {
        return horariosAjustados;
    }

    public void setHorariosAjustados(String horariosAjustados) {
        this.horariosAjustados = horariosAjustados;
    }

    public LocalTime getEntrada3() {
        return entrada3;
    }

    public void setEntrada3(LocalTime entrada3) {
        this.entrada3 = entrada3;
    }

    public LocalTime getSaida3() {
        return saida3;
    }

    public void setSaida3(LocalTime saida3) {
        this.saida3 = saida3;
    }
}
