package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.tela.InicioView;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Tela "Início": o dia de hoje, o que pede atenção, os saldos e os últimos dias. */
public record InicioResponse(LocalDate hoje, @JsonFormat(pattern = "HH:mm:ss") LocalTime agora, String saudacao,
                             String dataPorExtenso, Hoje dia, InicioView.Mes mes, InicioView.Banco banco,
                             List<Pendencia> pendencias, List<DiaResponse> ultimosDias) {

    public record Hoje(String situacao, String titulo, String detalhe, boolean trabalhando, int trabalhadoSegundos,
                       int previstoSegundos, int percentual, String resumo, String proximaBatida,
                       @JsonFormat(pattern = "HH:mm") LocalTime proximaPrevista, boolean podeBater,
                       String rotuloBotao, String aviso, DiaResponse dia) {
    }

    public record Pendencia(String tipo, String texto, String acao, LocalDate data, int quantidade) {
    }

    public static InicioResponse de(InicioView v) {
        InicioView.Hoje h = v.dia();
        return new InicioResponse(v.hoje(), v.agora(), v.saudacao(), v.dataPorExtenso(),
                new Hoje(h.situacao().name(), h.titulo(), h.detalhe(), h.trabalhando(), h.trabalhadoSegundos(),
                        h.previstoSegundos(), h.percentual(), h.resumo(), h.proximaBatida(), h.proximaPrevista(),
                        h.podeBater(), h.rotuloBotao(), h.aviso(), DiaResponse.de(h.dia())),
                v.mes(), v.banco(),
                v.pendencias().stream()
                        .map(p -> new Pendencia(p.tipo().name(), p.texto(), p.acao(), p.data(), p.quantidade())).toList(),
                v.ultimosDias().stream().map(DiaResponse::de).toList());
    }
}
