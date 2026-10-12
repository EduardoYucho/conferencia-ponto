package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A conversa com cada arquivo no lugar em que entrou: é o único material da redação, e fica guardada no atendimento
 * para a tela mostrar as fontes de cada parágrafo.
 *
 * @param itens      em ordem: as mensagens, os anexos logo depois da mensagem em que foram enviados e os arquivos
 *                   da pessoa pelo horário
 * @param semHorario os arquivos sem data confiável (o redator encaixa pelo conteúdo e diz isso no texto)
 */
public record LinhaDoTempo(int versao, Instant montadaEm, List<ItemDaLinha> itens, List<ItemDaLinha> semHorario) {

    public static final int VERSAO = 1;

    public LinhaDoTempo {
        itens = List.copyOf(itens);
        semHorario = List.copyOf(semHorario);
    }

    public Stream<ItemDaLinha> todos() {
        return Stream.concat(itens.stream(), semHorario.stream());
    }

    public Optional<ItemDaLinha> item(String id) {
        return todos().filter(i -> i.id().equals(id)).findFirst();
    }
}
