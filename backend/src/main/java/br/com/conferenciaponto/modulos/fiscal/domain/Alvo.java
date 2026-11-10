package br.com.conferenciaponto.modulos.fiscal.domain;

import java.util.Objects;

/**
 * O que é acompanhado: um serviço de um autorizador de um documento (ex.: NF-e, SVRS, "Autorização"). O serviço
 * {@link #GERAL} é o resumo do autorizador: é ele que pinta a célula do estado, abre os incidentes e conta na
 * disponibilidade.
 */
public record Alvo(Documento documento, String autorizador, String servico) {

    public static final String GERAL = "geral";

    public Alvo {
        Objects.requireNonNull(documento, "documento");
        if (autorizador == null || autorizador.isBlank() || servico == null || servico.isBlank()) {
            throw new IllegalArgumentException("Alvo sem autorizador ou serviço");
        }
    }

    public static Alvo geral(Documento documento, String autorizador) {
        return new Alvo(documento, autorizador, GERAL);
    }

    public boolean ehGeral() {
        return GERAL.equals(servico);
    }

    public Alvo doAutorizador() {
        return geral(documento, autorizador);
    }

    /** NFE:SVRS:Autorização */
    public String chave() {
        return documento.name() + ":" + autorizador + ":" + servico;
    }
}
