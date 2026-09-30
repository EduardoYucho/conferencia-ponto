package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Adapter da porta {@link RegistroJornadaRepository} usando Spring Data JPA. */
@Repository
class RegistroJornadaRepositoryAdapter implements RegistroJornadaRepository {

    private final RegistroJornadaJpaRepository jpa;

    RegistroJornadaRepositoryAdapter(RegistroJornadaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<RegistroJornada> buscarPorData(LocalDate data) {
        return jpa.findByDataReferencia(data).map(RegistroJornadaMapper::paraDominio);
    }

    @Override
    public List<RegistroJornada> listarPorPeriodo(LocalDate inicio, LocalDate fim) {
        return jpa.findByDataReferenciaBetweenOrderByDataReferenciaAsc(inicio, fim).stream()
                .map(RegistroJornadaMapper::paraDominio)
                .toList();
    }

    @Override
    public RegistroJornada salvar(RegistroJornada registro) {
        RegistroJornadaEntity entity = jpa.findById(registro.getId())
                .orElseGet(() -> new RegistroJornadaEntity(registro.getId(), registro.getDataReferencia()));
        RegistroJornadaMapper.copiar(registro, entity);
        jpa.save(entity);
        return registro;
    }

    @Override
    public void excluir(RegistroJornada registro) {
        jpa.deleteById(registro.getId());
    }

    @Override
    public List<SaldoMensal> consolidarAno(int ano) {
        return paraDominio(jpa.consolidarAno(ano));
    }

    @Override
    public List<SaldoMensal> consolidarPeriodo(LocalDate inicio, LocalDate fim) {
        return paraDominio(jpa.consolidarPeriodo(inicio, fim));
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
