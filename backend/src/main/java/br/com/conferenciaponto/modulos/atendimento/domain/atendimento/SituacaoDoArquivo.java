package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.util.Locale;

/** Situação de um arquivo do atendimento (o download e o envio; a análise tem a própria tabela). */
public enum SituacaoDoArquivo {
    AGUARDANDO,
    BAIXANDO,
    PRONTO,
    FALHOU,
    VENCIDO,
    NAO_SUPORTADO,
    REMOVIDO;

    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SituacaoDoArquivo doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }

    /** Já terminou (bem ou mal): conta para o percentual do processamento. */
    public boolean terminada() {
        return this != AGUARDANDO && this != BAIXANDO;
    }
}
