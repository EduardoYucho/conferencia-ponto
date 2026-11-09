package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;

/** Leituras prontas para os testes do monitor. */
public final class LeiturasDeTeste {

    public static final Instant T0 = Instant.parse("2026-11-09T13:00:00Z");

    private LeiturasDeTeste() {
    }

    public static Leitura servico(Documento documento, String autorizador, String servico, Situacao situacao) {
        return new Leitura(new Alvo(documento, autorizador, servico), Origem.PAINEL_SVRS, situacao,
                servico + ": " + situacao.name().toLowerCase(), situacao == Situacao.NORMAL ? 107 : 108, null, T0);
    }

    public static Leitura geral(Documento documento, String autorizador, Situacao situacao, String detalhe) {
        return new Leitura(Alvo.geral(documento, autorizador), Origem.PAINEL_SVRS, situacao, detalhe, null, null, T0);
    }

    public static Leitura conexao(Documento documento, String autorizador, Situacao situacao, Integer tempoMs) {
        return new Leitura(new Alvo(documento, autorizador, "Conexão"), Origem.TESTE_DE_CONEXAO, situacao,
                "teste: " + situacao, null, tempoMs, T0);
    }
}
