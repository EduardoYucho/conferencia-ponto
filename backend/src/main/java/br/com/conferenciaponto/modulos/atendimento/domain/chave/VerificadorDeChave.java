package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.util.Objects;

/** Pergunta ao Google se uma chave da API do Gemini funciona (sem gastar cota de geração). */
public interface VerificadorDeChave {

    Resultado testar(String chave);

    enum Tipo {
        /** O Google aceitou. */
        VALIDA,
        /** O Google recusou: chave inválida ou apagada, API desativada no projeto, restrição de uso. */
        RECUSADA,
        /** Aceita, mas sem cota agora. */
        SEM_COTA,
        /** O Google respondeu com erro dele ou não respondeu a tempo. */
        INDISPONIVEL,
        /** O servidor não conseguiu falar com o Google (sem internet, DNS, bloqueio da rede). */
        SEM_INTERNET
    }

    /** @param detalhe frase curta para a tela, sem a chave */
    record Resultado(Tipo tipo, String detalhe) {
        public Resultado {
            Objects.requireNonNull(tipo, "tipo");
        }
    }
}
