package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;

import java.time.Instant;
import java.util.List;

/** De onde vêm as leituras (um painel, os testes de conexão). Cada fonte trata os próprios erros. */
public interface FonteDeLeituras {

    /** Um nome curto e fixo ("painel-svrs-nfe", "testes-de-conexao"). */
    String nome();

    Resultado ler(Instant agora);

    /**
     * @param alcancou a fonte chegou a falar com alguém lá fora (uma página baixada, um servidor que respondeu); sem
     *                 nenhuma fonte que alcançou, a rodada é "sem internet neste computador"
     * @param erro     o que deu errado (vazio = tudo certo); a fonte pode ter alcançado e ainda assim ter problema
     *                 (ex.: a página mudou de formato)
     * @param aviso    o aviso de manutenção publicado (só nos painéis)
     */
    record Resultado(String fonte, boolean alcancou, String erro, List<Leitura> leituras, Documento documentoDoAviso,
                     String aviso) {

        public static Resultado falhou(String fonte, String erro) {
            return new Resultado(fonte, false, erro, List.of(), null, null);
        }
    }
}
