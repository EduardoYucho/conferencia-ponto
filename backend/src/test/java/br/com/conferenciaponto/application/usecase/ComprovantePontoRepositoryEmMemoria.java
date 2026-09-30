package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.port.ComprovantePontoRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

class ComprovantePontoRepositoryEmMemoria implements ComprovantePontoRepository {

    final List<ComprovanteImportado> salvos = new ArrayList<>();

    @Override
    public boolean existeHash(String hashSha256) {
        return salvos.stream().anyMatch(c -> c.hashSha256().equals(hashSha256));
    }

    @Override
    public Optional<ComprovanteImportado> buscarPorHash(String hashSha256) {
        return salvos.stream().filter(c -> c.hashSha256().equals(hashSha256)).findFirst();
    }

    @Override
    public boolean existeImportado(LocalDateTime dataHoraBatida) {
        return salvos.stream().anyMatch(c -> c.status() == StatusImportacao.IMPORTADO
                && dataHoraBatida.equals(c.dataHoraBatida()));
    }

    @Override
    public void salvar(ComprovanteImportado comprovante) {
        salvos.add(comprovante);
    }

    @Override
    public List<ComprovanteImportado> recentes(int limite) {
        return salvos.stream()
                .sorted(Comparator.comparing(ComprovanteImportado::processadoEm).reversed())
                .limit(limite)
                .toList();
    }
}
