package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Monta a linha do tempo, sem IA:
 *
 * <ul>
 *   <li>as mensagens e os eventos seguem a ordem do PDF;</li>
 *   <li>cada anexo entra logo depois da mensagem em que foi enviado (a posição do link no PDF);</li>
 *   <li>ligações, vídeos e prints entram pela data do arquivo, depois do último item do mesmo minuto ou de antes
 *       (o PDF só dá a hora e o minuto das mensagens, então, no empate, a mensagem vem primeiro);</li>
 *   <li>arquivo sem data, ou com data fora do atendimento (com uma margem), vai para "sem horário": a data de um
 *       arquivo copiado ou baixado de novo não diz quando ele foi feito.</li>
 * </ul>
 */
public final class MontadorDaLinhaDoTempo {

    private static final Comparator<MaterialDoArquivo> POR_ORIGEM_E_ORDEM = Comparator
            .comparingInt((MaterialDoArquivo a) -> ordemDaOrigem(a.origem()))
            .thenComparingInt(MaterialDoArquivo::ordem);

    private final Duration margem;

    /** @param margem quanto antes do início e depois do fim do atendimento a data de um arquivo ainda vale */
    public MontadorDaLinhaDoTempo(Duration margem) {
        this.margem = Objects.requireNonNull(margem);
    }

    /**
     * @param inicio e fim do atendimento (do cabeçalho do PDF); quando faltam, valem a primeira e a última mensagem
     *               com horário
     */
    public LinhaDoTempo montar(List<ItemDaConversa> conversa, List<MaterialDoArquivo> arquivos, Instant inicio,
                               Instant fim, Instant agora) {
        List<ItemDaConversa> ordenada = conversa.stream().sorted(Comparator.comparingInt(ItemDaConversa::ordem)).toList();
        Map<Integer, ItemDaConversa> porOrdem = new HashMap<>();
        ordenada.forEach(i -> porOrdem.put(i.ordem(), i));

        // anexos que se sabe em que mensagem foram enviados
        Map<Integer, List<MaterialDoArquivo>> naMensagem = new HashMap<>();
        List<MaterialDoArquivo> peloHorario = new ArrayList<>();
        for (MaterialDoArquivo arquivo : arquivos) {
            if (arquivo.daConversa() && arquivo.mensagemOrdem() != null && porOrdem.containsKey(arquivo.mensagemOrdem())) {
                naMensagem.computeIfAbsent(arquivo.mensagemOrdem(), o -> new ArrayList<>()).add(arquivo);
            } else {
                peloHorario.add(arquivo);
            }
        }

        Instant[] janela = janela(ordenada, inicio, fim);
        List<MaterialDoArquivo> comHorario = new ArrayList<>();
        List<MaterialDoArquivo> semHorario = new ArrayList<>();
        for (MaterialDoArquivo arquivo : peloHorario) {
            if (confiavel(arquivo.momento(), janela)) {
                comHorario.add(arquivo);
            } else {
                semHorario.add(arquivo);
            }
        }
        comHorario.sort(Comparator.comparing(MaterialDoArquivo::momento).thenComparing(POR_ORIGEM_E_ORDEM));
        semHorario.sort(POR_ORIGEM_E_ORDEM);

        // blocos: cada item da conversa com os anexos dele
        List<List<ItemDaLinha>> blocos = new ArrayList<>();
        List<Instant> minutos = new ArrayList<>();
        for (ItemDaConversa item : ordenada) {
            List<ItemDaLinha> bloco = new ArrayList<>();
            bloco.add(daConversa(item));
            naMensagem.getOrDefault(item.ordem(), List.of()).stream()
                    .sorted(Comparator.comparingInt(MaterialDoArquivo::ordem))
                    .forEach(a -> bloco.add(doArquivo(a, item)));
            blocos.add(bloco);
            minutos.add(item.momento() == null ? null : item.momento().truncatedTo(ChronoUnit.MINUTES));
        }

        // cada arquivo com horário entra depois do último bloco do mesmo minuto ou de antes
        Map<Integer, List<ItemDaLinha>> depoisDoBloco = new HashMap<>();
        for (MaterialDoArquivo arquivo : comHorario) {
            Instant minuto = arquivo.momento().truncatedTo(ChronoUnit.MINUTES);
            int posicao = -1;
            for (int i = 0; i < blocos.size(); i++) {
                if (minutos.get(i) != null && !minutos.get(i).isAfter(minuto)) {
                    posicao = i;
                }
            }
            depoisDoBloco.computeIfAbsent(posicao, p -> new ArrayList<>()).add(doArquivo(arquivo, null));
        }

        List<ItemDaLinha> itens = new ArrayList<>(depoisDoBloco.getOrDefault(-1, List.of()));
        for (int i = 0; i < blocos.size(); i++) {
            itens.addAll(blocos.get(i));
            itens.addAll(depoisDoBloco.getOrDefault(i, List.of()));
        }
        List<ItemDaLinha> semHora = semHorario.stream().map(a -> doArquivo(a, null)).toList();
        return new LinhaDoTempo(LinhaDoTempo.VERSAO, agora, itens, semHora);
    }

    /** O começo e o fim do atendimento, alargados pela margem. */
    private Instant[] janela(List<ItemDaConversa> conversa, Instant inicio, Instant fim) {
        Instant primeiro = conversa.stream().map(ItemDaConversa::momento).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
        Instant ultimo = conversa.stream().map(ItemDaConversa::momento).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        Instant de = inicio != null ? inicio : primeiro;
        Instant ate = fim != null ? fim : ultimo;
        if (primeiro != null && (de == null || primeiro.isBefore(de))) {
            de = primeiro;
        }
        if (ultimo != null && (ate == null || ultimo.isAfter(ate))) {
            ate = ultimo;
        }
        if (de == null || ate == null) {
            return null;
        }
        // o fim do chamado vem com hora e minuto: o minuto inteiro conta
        return new Instant[]{de.minus(margem), ate.plus(Duration.ofSeconds(59)).plus(margem)};
    }

    private static boolean confiavel(Instant momento, Instant[] janela) {
        return momento != null && janela != null && !momento.isBefore(janela[0]) && !momento.isAfter(janela[1]);
    }

    private static ItemDaLinha daConversa(ItemDaConversa item) {
        ItemDaLinha.TipoDoItem tipo = item.mensagem() ? ItemDaLinha.TipoDoItem.MENSAGEM : ItemDaLinha.TipoDoItem.EVENTO;
        return new ItemDaLinha("M" + item.ordem(), tipo, item.momento(), item.lado() == null ? null : item.lado().codigo(),
                item.remetente(), item.texto(), null, null, null, null, null, null, null);
    }

    private static ItemDaLinha doArquivo(MaterialDoArquivo arquivo, ItemDaConversa mensagem) {
        Instant momento = mensagem != null ? mensagem.momento() : arquivo.momento();
        return new ItemDaLinha(arquivo.identificador(), ItemDaLinha.TipoDoItem.ARQUIVO, momento,
                mensagem == null || mensagem.lado() == null ? null : mensagem.lado().codigo(),
                mensagem == null ? null : mensagem.remetente(), arquivo.conteudo(), arquivo.id(), arquivo.origem(),
                arquivo.categoria(), arquivo.nome(), mensagem == null ? null : "M" + mensagem.ordem(), arquivo.situacao(),
                arquivo.resumo());
    }

    private static int ordemDaOrigem(String origem) {
        return switch (origem) {
            case MaterialDoArquivo.ANEXO -> 0;
            case MaterialDoArquivo.LIGACAO -> 1;
            case MaterialDoArquivo.PRINT -> 2;
            case MaterialDoArquivo.VIDEO -> 3;
            default -> 4;
        };
    }
}
