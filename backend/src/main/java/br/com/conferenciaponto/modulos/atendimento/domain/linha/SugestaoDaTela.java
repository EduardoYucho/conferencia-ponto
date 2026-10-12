package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sugestão para o campo Tela/Funcionalidade dos chamados pelos códigos lidos nas imagens: a tela que mais aparece
 * nos prints analisados, de preferência com o código ("Pedidos de Venda (EQR76)"). No empate, a que aparece primeiro.
 */
public final class SugestaoDaTela {

    /** O resumo de um print analisado: "tela Pedidos de Venda (EQR76)" ou "tela Pedidos de Venda". */
    private static final Pattern TELA = Pattern.compile("^tela (.+?)(?: \\(([^()]{1,40})\\))?$");

    private SugestaoDaTela() {
    }

    public static Optional<String> de(LinhaDoTempo linha) {
        Map<String, Integer> comCodigo = new LinkedHashMap<>();
        Map<String, Integer> semCodigo = new LinkedHashMap<>();
        linha.todos()
                .filter(ItemDaLinha::arquivo)
                .filter(i -> "imagem".equals(i.categoria()) && "analisado".equals(i.situacao()) && i.resumo() != null)
                .forEach(i -> {
                    Matcher m = TELA.matcher(i.resumo().strip());
                    if (!m.matches()) {
                        return;
                    }
                    String nome = m.group(1).strip();
                    if (m.group(2) != null) {
                        comCodigo.merge(nome + " (" + m.group(2).strip() + ")", 1, Integer::sum);
                    } else {
                        semCodigo.merge(nome, 1, Integer::sum);
                    }
                });
        return maisFrequente(comCodigo).or(() -> maisFrequente(semCodigo));
    }

    private static Optional<String> maisFrequente(Map<String, Integer> contagem) {
        String melhor = null;
        int vezes = 0;
        for (Map.Entry<String, Integer> e : contagem.entrySet()) {
            if (e.getValue() > vezes) {
                melhor = e.getKey();
                vezes = e.getValue();
            }
        }
        return Optional.ofNullable(melhor);
    }
}
