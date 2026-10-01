package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class LancamentoBancoRepositoryAdapter implements LancamentoBancoRepository {

    private final LancamentoBancoJpaRepository jpa;

    LancamentoBancoRepositoryAdapter(LancamentoBancoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public LancamentoBanco salvar(LancamentoBanco l) {
        jpa.save(new LancamentoBancoEntity(l.id(), l.data(), l.segundos(), l.descricao(),
                l.criadoEm().atOffset(ZoneOffset.UTC), l.criadoPor()));
        return l;
    }

    @Override
    public Optional<LancamentoBanco> buscarPorId(UUID id) {
        return jpa.findById(id).map(LancamentoBancoRepositoryAdapter::paraDominio);
    }

    @Override
    public void excluir(UUID id) {
        jpa.deleteById(id);
    }

    @Override
    public List<LancamentoBanco> listarNoPeriodo(LocalDate inicio, LocalDate fim) {
        return jpa.findByDataBetweenOrderByDataAscCriadoEmAsc(inicio, fim).stream()
                .map(LancamentoBancoRepositoryAdapter::paraDominio).toList();
    }

    private static LancamentoBanco paraDominio(LancamentoBancoEntity e) {
        return new LancamentoBanco(e.getId(), e.getData(), e.getSegundos(), e.getDescricao(),
                e.getCriadoEm().toInstant(), e.getCriadoPor());
    }
}
