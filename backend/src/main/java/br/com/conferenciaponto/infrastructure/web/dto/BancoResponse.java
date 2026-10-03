package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.tela.BancoView;
import br.com.conferenciaponto.application.tela.InicioView;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Tela "Banco de horas": saldo do ciclo, meses, horas usadas ou somadas à mão e fechamentos anteriores. */
public record BancoResponse(InicioView.Banco resumo, CicloBancoResponse ciclo, List<BancoView.Mes> meses,
                            List<Lancamento> lancamentos, List<Anterior> anteriores, boolean podeEditar,
                            boolean podeDesfazer, String explicacao) {

    public record Lancamento(UUID id, LocalDate data, String dia, int segundos, String texto, String descricao,
                             Instant criadoEm, String criadoPor) {

        static Lancamento de(BancoView.Lancamento l) {
            LancamentoBanco b = l.lancamento();
            return new Lancamento(b.id(), b.data(), l.dia(), b.segundos(), l.texto(), b.descricao(), b.criadoEm(),
                    b.criadoPor());
        }
    }

    public record Anterior(UUID id, LocalDate dataInicio, LocalDate dataFim, String periodo, Integer saldoSegundos,
                           String texto, Instant fechadoEm, String fechadoPor, String observacao) {

        static Anterior de(BancoView.Anterior a) {
            CicloBanco c = a.ciclo();
            return new Anterior(c.id(), c.dataInicio(), c.dataFim(), a.periodo(), c.saldoFinalSegundos(), a.texto(),
                    c.fechadoEm(), c.fechadoPor(), c.observacao());
        }
    }

    public static BancoResponse de(BancoView v) {
        return new BancoResponse(v.resumo(), v.ciclo() == null ? null : CicloBancoResponse.de(v.ciclo()), v.meses(),
                v.lancamentos().stream().map(Lancamento::de).toList(),
                v.anteriores().stream().map(Anterior::de).toList(), v.podeEditar(), v.podeDesfazer(), v.explicacao());
    }
}
