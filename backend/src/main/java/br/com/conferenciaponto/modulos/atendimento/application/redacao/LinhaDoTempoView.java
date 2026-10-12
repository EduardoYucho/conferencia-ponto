package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A linha do tempo usada na redação, para a tela mostrar a fonte de cada trecho. */
public record LinhaDoTempoView(Instant montadaEm, List<Item> itens) {

    /**
     * @param semHorario arquivo sem data confiável (o redator encaixou pelo conteúdo)
     * @param texto      a mensagem; nos arquivos, o que a análise leu
     */
    public record Item(String id, String tipo, Instant momento, String lado, String remetente, String texto,
                       UUID arquivoId, String origem, String categoria, String nome, String mensagem, String situacao,
                       boolean semHorario) {
    }
}
