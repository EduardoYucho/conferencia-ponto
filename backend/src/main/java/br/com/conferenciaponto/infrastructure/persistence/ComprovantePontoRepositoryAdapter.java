package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ComprovantePontoRepositoryAdapter implements ComprovantePontoRepository {

    private final ComprovantePontoJpaRepository jpa;

    ComprovantePontoRepositoryAdapter(ComprovantePontoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existeHash(String hashSha256) {
        return jpa.existsByHashSha256(hashSha256);
    }

    @Override
    public Optional<ComprovanteImportado> buscarPorHash(String hashSha256) {
        return jpa.findByHashSha256(hashSha256).map(ComprovantePontoEntity::paraDominio);
    }

    @Override
    public boolean existeImportado(UUID usuarioId, LocalDateTime dataHoraBatida) {
        return jpa.existsByUsuarioIdAndDataHoraBatidaAndStatus(usuarioId, dataHoraBatida, StatusImportacao.IMPORTADO);
    }

    @Override
    public void salvar(ComprovanteImportado comprovante) {
        jpa.save(ComprovantePontoEntity.de(comprovante));
    }

    @Override
    public List<ComprovanteImportado> recentes(UUID usuarioId, int limite) {
        return jpa.findByUsuarioIdOrderByProcessadoEmDesc(usuarioId, PageRequest.of(0, limite)).stream()
                .map(ComprovantePontoEntity::paraDominio)
                .toList();
    }
}
