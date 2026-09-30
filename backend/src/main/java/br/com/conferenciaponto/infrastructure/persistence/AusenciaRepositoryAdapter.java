package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.CalendarioAusencias;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class AusenciaRepositoryAdapter implements AusenciaRepository, CalendarioAusencias {

    private final AusenciaJpaRepository jpa;

    AusenciaRepositoryAdapter(AusenciaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void salvar(Ausencia a) {
        AusenciaEntity e = jpa.findById(a.id()).orElseGet(() -> new AusenciaEntity(a.id()));
        e.setDataInicio(a.dataInicio());
        e.setDataFim(a.dataFim());
        e.setTipo(a.tipo().name());
        e.setDescricao(a.descricao());
        e.setCriadoEm(a.criadoEm().atOffset(ZoneOffset.UTC));
        e.setCriadoPor(a.criadoPor());
        jpa.save(e);
    }

    @Override
    public void excluir(UUID id) {
        jpa.deleteById(id);
    }

    @Override
    public Optional<Ausencia> buscarPorId(UUID id) {
        return jpa.findById(id).map(AusenciaRepositoryAdapter::paraDominio);
    }

    @Override
    public List<Ausencia> listarNoPeriodo(LocalDate inicio, LocalDate fim) {
        return jpa.noPeriodo(inicio, fim).stream().map(AusenciaRepositoryAdapter::paraDominio).toList();
    }

    @Override
    public boolean isAusencia(LocalDate data) {
        return jpa.existsByDataInicioLessThanEqualAndDataFimGreaterThanEqual(data, data);
    }

    private static Ausencia paraDominio(AusenciaEntity e) {
        return new Ausencia(e.getId(), e.getDataInicio(), e.getDataFim(), TipoAusencia.valueOf(e.getTipo()),
                e.getDescricao(), e.getCriadoEm().toInstant(), e.getCriadoPor());
    }
}
