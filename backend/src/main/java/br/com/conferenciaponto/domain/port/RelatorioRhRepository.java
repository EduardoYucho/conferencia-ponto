package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRh;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelatorioRhRepository {

    /** Grava o relatório (e, na primeira vez, os dias lidos). */
    void salvar(RelatorioRh relatorio, List<DiaRelatorioRh> dias);

    void atualizar(RelatorioRh relatorio);

    Optional<RelatorioRh> buscarPorId(UUID id);

    Optional<RelatorioRh> buscarPorHash(String hashSha256);

    /** Do mais recente (emissão) para o mais antigo. */
    List<RelatorioRh> listar();

    void excluir(UUID id);

    List<DiaRelatorioRh> dias(UUID relatorioId);

    /** Um dia no relatório vigente para ele. */
    record DiaVigente(UUID relatorioId, DiaRelatorioRh dia) {
    }

    record Abrangencia(LocalDate inicio, LocalDate fim) {
    }

    /**
     * Para cada data do período, o dia do relatório mais recente (pela emissão) que a contém — é o que
     * vale para a conciliação. Relatórios com erro ficam de fora.
     */
    List<DiaVigente> vigentes(LocalDate inicio, LocalDate fim);

    /** Primeira e última data cobertas por algum relatório (vazio se não houver relatórios). */
    Optional<Abrangencia> abrangencia();
}
