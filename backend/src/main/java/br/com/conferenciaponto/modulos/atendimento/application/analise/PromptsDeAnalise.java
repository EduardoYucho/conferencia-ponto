package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.application.ia.ArquivosDePrompt;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As instruções e os esquemas da análise de cada tipo de arquivo, lidos dos arquivos em
 * {@code resources/atendimento/prompts} e {@code esquemas} ({@link ArquivosDePrompt}). A versão fica gravada com a
 * análise: mudar o texto de um prompt exige um arquivo da versão seguinte (as análises antigas continuam valendo
 * para o que já foi redigido, e os arquivos são analisados de novo só quando a pessoa processar outra vez).
 *
 * <p>Vídeo fica para a etapa do vídeo (quadros e passos); "outro" não é analisado.
 */
public final class PromptsDeAnalise {

    /**
     * Um prompt.
     *
     * @param obrigatorios os campos do topo que a resposta precisa ter (a conferência antes de gravar)
     * @param listas       quais desses campos são listas
     */
    public record Prompt(String tipo, int versao, String instrucao, String esquema, List<String> obrigatorios,
                         List<String> listas, boolean altaResolucao) {
    }

    /** As regras comuns, que valem para qualquer arquivo. */
    public static final String SISTEMA = ArquivosDePrompt.prompt("sistema-analise", 1);

    private static final Prompt IMAGEM = carregar("imagem", 1, true);
    private static final Prompt AUDIO = carregar("audio", 1, false);
    private static final Prompt DOCUMENTO = carregar("documento", 1, false);

    /** O prompt e o esquema dos arquivos; os obrigatórios e as listas vêm do esquema. */
    private static Prompt carregar(String tipo, int versao, boolean altaResolucao) {
        String esquema = ArquivosDePrompt.esquema(tipo, versao);
        return new Prompt(tipo, versao, ArquivosDePrompt.prompt(tipo, versao), esquema,
                ArquivosDePrompt.obrigatorios(esquema), ArquivosDePrompt.listas(esquema), altaResolucao);
    }

    private static final Map<CategoriaDoArquivo, Prompt> POR_CATEGORIA = Map.of(
            CategoriaDoArquivo.IMAGEM, IMAGEM,
            CategoriaDoArquivo.AUDIO, AUDIO,
            CategoriaDoArquivo.DOCUMENTO, DOCUMENTO);

    private PromptsDeAnalise() {
    }

    /** O prompt da categoria, ou vazio quando o tipo não é analisado (vídeo nesta versão, "outro"). */
    public static Optional<Prompt> para(CategoriaDoArquivo categoria) {
        return Optional.ofNullable(categoria == null ? null : POR_CATEGORIA.get(categoria));
    }
}
