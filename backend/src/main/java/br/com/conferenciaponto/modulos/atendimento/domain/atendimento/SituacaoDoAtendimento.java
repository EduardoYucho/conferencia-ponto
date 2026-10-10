package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.util.Locale;

/** Situação do atendimento (nesta etapa todos nascem "novo"; a fila das próximas etapas muda as outras). */
public enum SituacaoDoAtendimento {
    NOVO,
    PROCESSANDO,
    PAUSADO,
    PRONTO,
    COM_FALHAS,
    CANCELADO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SituacaoDoAtendimento doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
