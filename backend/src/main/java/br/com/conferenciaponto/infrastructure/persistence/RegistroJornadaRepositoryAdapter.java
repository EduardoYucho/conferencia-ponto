package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Adapter da porta {@link RegistroJornadaRepository} usando Spring Data JPA. */
@Repository
class RegistroJornadaRepositoryAdapter implements RegistroJornadaRepository {

    private final RegistroJornadaJpaRepository jpa;

    RegistroJornadaRepositoryAdapter(RegistroJornadaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RegistroJornada> buscarPorData(UUID usuarioId, LocalDate data) {
        return jpa.findByUsuarioIdAndDataReferencia(usuarioId, data).map(RegistroJornadaMapper::paraDominio);
    }

    @Override
    public Optional<RegistroJornada> buscarPorId(UUID id) {
        return jpa.findById(id).map(RegistroJornadaMapper::paraDominio);
    }

    @Override
    public List<RegistroJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return jpa.findByUsuarioIdAndDataReferenciaBetweenOrderByDataReferenciaAsc(usuarioId, inicio, fim).stream()
                .map(RegistroJornadaMapper::paraDominio)
                .toList();
    }

    @Override
    public List<RegistroJornada> listarPorData(LocalDate data) {
        return jpa.findByDataReferencia(data).stream().map(RegistroJornadaMapper::paraDominio).toList();
    }

    @Override
    public List<UUID> usuariosComRegistros() {
        return jpa.usuariosComRegistros();
    }

    @Override
    public RegistroJornada salvar(RegistroJornada registro) {
        RegistroJornadaEntity entity = jpa.findById(registro.getId())
                .orElseGet(() -> new RegistroJornadaEntity(registro.getId(), registro.getUsuarioId(),
                        registro.getDataReferencia()));
        RegistroJornadaMapper.copiar(registro, entity);
        jpa.save(entity);
        return registro;
    }

    @Override
    public void excluir(RegistroJornada registro) {
        jpa.deleteById(registro.getId());
    }

    @Override
    public List<SaldoMensal> consolidarAno(UUID usuarioId, int ano) {
        return paraDominio(jpa.consolidarAno(usuarioId, ano));
    }

    @Override
    public List<SaldoMensal> consolidarPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return paraDominio(jpa.consolidarPeriodo(usuarioId, inicio, fim));
    }

    private static List<SaldoMensal> paraDominio(List<SaldoMensalProjection> projecoes) {
        return projecoes.stream()
                .map(p -> new SaldoMensal(
                        p.getAno(),
                        p.getMes(),
                        p.getDiasRegistrados(),
                        p.getDiasEmAberto(),
                        p.getSegundosTrabalhados(),
                        p.getSegundosPrevistos(),
                        p.getSaldoMensalSegundos(),
                        p.getSaldoAnualAcumuladoSegundos()))
                .toList();
    }
}
