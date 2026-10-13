package br.com.conferenciaponto.modulos.atendimento.domain.fila;

import java.time.Duration;

/**
 * Como terminou uma tarefa. A mensagem é para a pessoa ler (vai para a tela); o código, para o suporte.
 */
public sealed interface ResultadoDaTarefa {

    /** Feita. */
    record Concluida() implements ResultadoDaTarefa {
    }

    /**
     * Falhou por algo passageiro (rede, servidor ocupado): volta para a fila com espera crescente, até o limite de
     * tentativas. {@code esperar} é o tempo pedido pelo outro lado (ou null, para a espera padrão).
     *
     * @param aoEsgotar com as tentativas esgotadas, em vez de falhar de vez a tarefa pausa o atendimento com esta
     *                  mensagem (null: falha de vez). Serve para o que não pode ser deixado para trás sem a pessoa
     *                  saber, como a análise de um arquivo quando o Gemini está sobrecarregado.
     */
    record FalhaTemporaria(String codigo, String mensagem, Duration esperar, String aoEsgotar) implements ResultadoDaTarefa {

        public FalhaTemporaria(String codigo, String mensagem, Duration esperar) {
            this(codigo, mensagem, esperar, null);
        }
    }

    /** Falhou de um jeito que repetir não resolve (link vencido, arquivo grande demais): para só este item. */
    record FalhaDefinitiva(String codigo, String mensagem) implements ResultadoDaTarefa {
    }

    /** Não dá para continuar o atendimento agora (disco cheio): ele fica pausado até a pessoa retomar. */
    record Pausar(String codigo, String mensagem) implements ResultadoDaTarefa {
    }

    static ResultadoDaTarefa concluida() {
        return new Concluida();
    }

    static ResultadoDaTarefa temporaria(String codigo, String mensagem) {
        return new FalhaTemporaria(codigo, mensagem, null);
    }

    /** Passageira, esperando pelo menos o tempo pedido (ex.: o limite por minuto do Gemini). */
    static ResultadoDaTarefa temporaria(String codigo, String mensagem, Duration esperar) {
        return new FalhaTemporaria(codigo, mensagem, esperar);
    }

    /** Passageira; se as tentativas acabarem, pausa o atendimento com {@code aoEsgotar} (em vez de falhar de vez). */
    static ResultadoDaTarefa temporariaQuePausa(String codigo, String mensagem, Duration esperar, String aoEsgotar) {
        return new FalhaTemporaria(codigo, mensagem, esperar, aoEsgotar);
    }

    static ResultadoDaTarefa definitiva(String codigo, String mensagem) {
        return new FalhaDefinitiva(codigo, mensagem);
    }

    static ResultadoDaTarefa pausar(String codigo, String mensagem) {
        return new Pausar(codigo, mensagem);
    }
}
