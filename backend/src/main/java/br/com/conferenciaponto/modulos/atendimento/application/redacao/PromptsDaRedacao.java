package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.application.ia.ArquivosDePrompt;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As instruções e os esquemas da redação de cada tipo de saída, lidos dos arquivos em
 * {@code resources/atendimento/prompts} e {@code esquemas}. A versão fica gravada com a saída.
 */
final class PromptsDaRedacao {

    /** Um prompt: o texto pode ter marcadores {@code {{NOME}}} trocados na hora. */
    record Prompt(String nome, int versao, String instrucao, String esquema, List<String> obrigatorios,
                  List<String> listas) {

        String instrucao(Map<String, String> valores) {
            String texto = instrucao;
            for (Map.Entry<String, String> valor : valores.entrySet()) {
                texto = texto.replace("{{" + valor.getKey() + "}}", valor.getValue());
            }
            return texto;
        }
    }

    /** As regras comuns da redação. */
    static final String SISTEMA = ArquivosDePrompt.prompt("sistema-redacao", 3);

    static final Prompt RESUMO = carregar("resumo", 3);
    static final Prompt DEV_ERRO = carregar("dev-erro", 1);
    static final Prompt DEV_ALTERACAO = carregar("dev-alteracao", 2);
    /** Um assunto mais curto, quando o primeiro passou do limite. */
    static final Prompt ASSUNTO_CURTO = carregar("assunto-curto", 1);

    private static final Map<TipoDeSaida, Prompt> POR_TIPO = Map.of(TipoDeSaida.RESUMO, RESUMO,
            TipoDeSaida.DEV_ERRO, DEV_ERRO, TipoDeSaida.DEV_ALTERACAO, DEV_ALTERACAO);

    private PromptsDaRedacao() {
    }

    static Optional<Prompt> para(TipoDeSaida tipo) {
        return Optional.ofNullable(POR_TIPO.get(tipo));
    }

    private static Prompt carregar(String nome, int versao) {
        String esquema = ArquivosDePrompt.esquema(nome, versao);
        return new Prompt(nome, versao, ArquivosDePrompt.prompt(nome, versao), esquema,
                ArquivosDePrompt.obrigatorios(esquema), ArquivosDePrompt.listas(esquema));
    }
}
