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

    /** A versão da habilidade que a IA local segue (as mesmas regras do app do Gemini). */
    static final int VERSAO_DA_HABILIDADE = 2;

    /**
     * As regras da IA local: as da habilidade (texto corrido, sem numerar, o formato do chamado), com a primeira parte
     * trocada, porque o material chega em texto, já lido (e não como o pacote de arquivos do app do Gemini).
     */
    static final String SISTEMA_LOCAL = sistemaLocal(ArquivosDePrompt.prompt("habilidade", VERSAO_DA_HABILIDADE));

    private static final String COMO_VEM_O_MATERIAL = """
            O QUE VEM EM CADA CONVERSA
            O material vem em texto: a conversa do chat com o cliente, em ordem, cada item com um identificador entre \
            colchetes ([M12] mensagens e eventos, [A3] arquivos enviados na conversa, [L1] gravações de ligação, [V1] \
            vídeos de reprodução, [P1] prints do atendente). No lugar de cada arquivo vem o conteúdo dele, já lido por \
            outro programa: a descrição da imagem, a transcrição do áudio, o texto do documento ou o que aparece e o que \
            é falado no vídeo (com os instantes). O que foi falado nos áudios e mostrado nas imagens, documentos e vídeos \
            faz parte do atendimento tanto quanto as mensagens; é ali que costumam estar os detalhes (o erro na tela, o \
            relatório pedido, os campos). Arquivo sem conteúdo: diga isso no texto, no ponto em que ele entrou, sem \
            supor o conteúdo.

            """;

    static String sistemaLocal(String habilidade) {
        String texto = habilidade.replace("\r\n", "\n");
        int inicio = texto.indexOf("O QUE VEM EM CADA CONVERSA");
        String depois = "Depois dos arquivos, a pessoa escreve o que quer:";
        int fim = texto.indexOf(depois);
        if (inicio < 0 || fim < inicio) {
            return texto;
        }
        return texto.substring(0, inicio) + COMO_VEM_O_MATERIAL
                + texto.substring(fim).replace(depois, "No fim, a pessoa escreve o que quer:");
    }

    /**
     * O que vai depois do material, para a IA local: o pedido do tipo e a conferência do formato (um modelo menor
     * segue melhor o formato quando ele vem lembrado no fim).
     *
     * @param atendente quem fez o atendimento (o resumo é em primeira pessoa, como ele)
     */
    static String lembreteLocal(TipoDeSaida tipo, String contato, String atendente) {
        String quem = contato == null || contato.isBlank() ? "contato não identificado" : contato.strip();
        String imagens = "- Cada imagem que ajuda a entender entra numa linha sozinha, logo depois do trecho que ela "
                + "ilustra, assim: [Imagem A1: legenda curta, de no máximo 12 palavras]. Só as da lista \"Imagens que o "
                + "texto pode mostrar\", cada uma no máximo uma vez.";
        String fim = "- Nada antes do Assunto e nada depois do texto.";
        return switch (tipo) {
            case RESUMO -> "Escreva agora o \"resumo\". Confira antes de responder:\n"
                    + "- A primeira linha é exatamente: Assunto: Digisac: " + quem + " : <o assunto em poucas palavras>\n"
                    + "- Depois, uma linha em branco e o relato em primeira pessoa, como "
                    + (atendente == null || atendente.isBlank() ? "o atendente do suporte" : atendente.strip() + ", o atendente do suporte")
                    + " (\"verifiquei\", \"pedi\", \"informei\"): o cliente é \"o cliente\" ou \"a cliente\", nunca \"eu\". "
                    + "O que o cliente disse fica com o cliente.\n"
                    + "- Parágrafos curtos, em ordem cronológica, sem títulos, sem listas e sem números no começo.\n"
                    + imagens + "\n" + fim;
            case DEV_ERRO -> "Escreva agora o chamado de \"erro\". Confira antes de responder:\n"
                    + "- Se o atendimento tem mais de um problema, o chamado é do problema que o atendente disse que vai "
                    + "encaminhar ao desenvolvimento.\n"
                    + "- A primeira linha é exatamente: Assunto: Digisac: " + quem + " : <o problema em poucas palavras>\n"
                    + "- A segunda linha: Tela/Funcionalidade: <Nome da tela (CÓDIGO)>\n"
                    + "- Depois, as partes do chamado de erro, na ordem, cada uma com o título numa linha própria "
                    + "(## Título da parte); parte sem nada no material fica de fora.\n"
                    + "- Terceira pessoa, frases curtas, sem listas e sem números no começo.\n"
                    + imagens + "\n" + fim;
            case DEV_ALTERACAO -> "Escreva agora o chamado de \"alteração\". Confira antes de responder:\n"
                    + "- A primeira linha é exatamente: Assunto: Digisac: " + quem + " : <o pedido em poucas palavras>\n"
                    + "- A segunda linha: Tela/Funcionalidade: <Nome da tela (CÓDIGO)>\n"
                    + "- Depois, a linha Solicitação de Melhoria e as partes do chamado de alteração, na ordem, cada uma "
                    + "com o título numa linha própria (## Título da parte); parte sem nada no material fica de fora.\n"
                    + "- Terceira pessoa, frases curtas, sem listas e sem números no começo.\n"
                    + imagens + "\n" + fim;
        };
    }

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
