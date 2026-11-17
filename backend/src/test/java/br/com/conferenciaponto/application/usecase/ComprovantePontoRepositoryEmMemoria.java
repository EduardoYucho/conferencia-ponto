package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class ComprovantePontoRepositoryEmMemoria implements ComprovantePontoRepository {

    final List<ComprovanteImportado> salvos = new ArrayList<>();

    @Override
    public boolean existeHash(String hashSha256) {
        return salvos.stream().anyMatch(c -> c.hashSha256().equals(hashSha256));
    }

    @Override
    public Optional<ComprovanteImportado> buscarPorHash(UUID usuarioId, String hashSha256) {
        return salvos.stream().filter(c -> c.usuarioId().equals(usuarioId) && c.hashSha256().equals(hashSha256))
                .findFirst();
    }

    @Override
    public boolean noPontoDeOutraPessoa(String hashSha256, UUID usuarioId) {
        return salvos.stream().anyMatch(c -> c.hashSha256().equals(hashSha256) && !c.usuarioId().equals(usuarioId)
                && (c.status() == StatusImportacao.IMPORTADO || c.status() == StatusImportacao.DUPLICADO));
    }

    @Override
    public void marcarDesfeito(UUID id, String mensagem) {
        salvos.replaceAll(c -> c.id().equals(id) ? new ComprovanteImportado(c.id(), c.usuarioId(), c.nomeArquivo(),
                c.hashSha256(), c.dataHoraBatida(), StatusImportacao.DESFEITO, mensagem, c.processadoEm()) : c);
    }

    @Override
    public boolean existeImportado(UUID usuarioId, LocalDateTime dataHoraBatida) {
        return salvos.stream().anyMatch(c -> c.usuarioId().equals(usuarioId) && c.status() == StatusImportacao.IMPORTADO
                && dataHoraBatida.equals(c.dataHoraBatida()));
    }

    @Override
    public void salvar(ComprovanteImportado comprovante) {
        if (buscarPorHash(comprovante.usuarioId(), comprovante.hashSha256()).isPresent()) {
            throw new IllegalStateException("uk_comprovante_ponto_usuario_hash");
        }
        salvos.add(comprovante);
    }

    @Override
    public List<ComprovanteImportado> recentes(UUID usuarioId, int limite) {
        return salvos.stream()
                .filter(c -> c.usuarioId().equals(usuarioId))
                .sorted(Comparator.comparing(ComprovanteImportado::processadoEm).reversed())
                .limit(limite)
                .toList();
    }
}
