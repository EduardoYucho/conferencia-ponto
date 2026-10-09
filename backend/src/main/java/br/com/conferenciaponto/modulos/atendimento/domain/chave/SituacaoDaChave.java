package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.util.Locale;

/** Resultado do último teste da chave do Gemini com o Google (coluna atendimento.chave_gemini.situacao). */
public enum SituacaoDaChave {
    /** Guardada sem teste (não acontece no cadastro: a chave é testada antes de ser guardada). */
    NAO_TESTADA,
    /** O Google aceitou a chave. */
    VALIDA,
    /** O Google recusou a chave (inválida, apagada, API desativada no projeto ou com restrição). */
    RECUSADA,
    /** A chave é aceita, mas a cota acabou (por minuto ou do dia). */
    SEM_COTA;

    /** Como fica no banco e na API: nao_testada, valida, recusada, sem_cota. */
    public String codigo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SituacaoDaChave doCodigo(String codigo) {
        return valueOf(codigo.toUpperCase(Locale.ROOT));
    }
}
