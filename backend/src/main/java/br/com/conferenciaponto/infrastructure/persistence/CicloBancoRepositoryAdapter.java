package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Grava com flush imediato: o índice "um só ciclo ABERTO" não é adiável e o Hibernate faria o INSERT
 * do novo ciclo antes do UPDATE que fecha o anterior.
 */
@Repository
class CicloBancoRepositoryAdapter implements CicloBancoRepository {

    private final CicloBancoJpaRepository jpa;

    CicloBancoRepositoryAdapter(CicloBancoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<CicloBanco> buscarAberto() {
        return jpa.findFirstByStatus(StatusCiclo.ABERTO.name()).map(CicloBancoRepositoryAdapter::paraDominio);
    }

    @Override
    public List<CicloBanco> listar() {
        return jpa.findAllByOrderByDataInicioDesc().stream().map(CicloBancoRepositoryAdapter::paraDominio).toList();
    }

    @Override
    public void salvar(CicloBanco c) {
        CicloBancoEntity e = jpa.findById(c.id()).orElseGet(() -> new CicloBancoEntity(c.id()));
        e.setDataInicio(c.dataInicio());
        e.setDataFim(c.dataFim());
        e.setDataFimPrevista(c.dataFimPrevista());
        e.setStatus(c.status().name());
        e.setSaldoFinalSegundos(c.saldoFinalSegundos());
        e.setFechadoEm(c.fechadoEm() == null ? null : c.fechadoEm().atOffset(ZoneOffset.UTC));
        e.setFechadoPor(c.fechadoPor());
        e.setObservacao(c.observacao());
        e.setCriadoEm(c.criadoEm().atOffset(ZoneOffset.UTC));
        jpa.saveAndFlush(e);
    }

    @Override
    public void excluir(UUID id) {
        jpa.deleteById(id);
        jpa.flush();
    }

    private static CicloBanco paraDominio(CicloBancoEntity e) {
        return new CicloBanco(e.getId(), e.getDataInicio(), e.getDataFim(), e.getDataFimPrevista(),
                StatusCiclo.valueOf(e.getStatus()), e.getSaldoFinalSegundos(),
                e.getFechadoEm() == null ? null : e.getFechadoEm().toInstant(), e.getFechadoPor(), e.getObservacao(),
                e.getCriadoEm().toInstant());
    }
}
