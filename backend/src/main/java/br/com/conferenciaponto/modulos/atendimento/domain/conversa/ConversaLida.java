package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tudo o que o {@link LeitorConversaDigisac} tirou do PDF.
 *
 * @param linksIgnorados  links que não são anexo (propaganda do Digisac no rodapé, sites citados)
 * @param linhasSemCabecalho linhas de mensagem que não tinham remetente e horário acima (o leitor guarda o texto mesmo
 *                        assim, numa mensagem sem remetente); muitas indicam que o layout mudou
 */
public record ConversaLida(Cabecalho cabecalho, List<ItemDaConversa> itens, List<AnexoLido> anexos, int linksIgnorados,
                           int linhasSemCabecalho) {

    public ConversaLida {
        itens = List.copyOf(itens);
        anexos = List.copyOf(anexos);
    }

    public long mensagens() {
        return itens.stream().filter(ItemDaConversa::mensagem).count();
    }

    public long eventos() {
        return itens.size() - mensagens();
    }

    /** O link que vence primeiro (os do mesmo PDF vencem quase juntos, 24 h depois da exportação). */
    public Instant linksValidosAte() {
        return anexos.stream().map(AnexoLido::validoAte).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
    }

    public ConversaLida com(Cabecalho novoCabecalho, List<ItemDaConversa> novosItens) {
        return new ConversaLida(novoCabecalho, novosItens, anexos, linksIgnorados, linhasSemCabecalho);
    }
}
