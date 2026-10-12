package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * O que o autor (ou quem cura a base) escreve no registro: a identificação, o problema e a resolução. É o que entra na
 * pesquisa (pesos: A para título, código, tela e erro; B para sintoma, causa, módulo e cliente; C para a resolução).
 */
public record DadosDoRegistro(String titulo, TipoDoRegistro tipo, String cliente, String contato, String modulo,
                              String telaNome, String telaCodigo, String sintoma, String mensagemErro, String causa,
                              String resolucao) {

    public static final int LIMITE_DO_TITULO = 200;
    public static final int LIMITE_DO_CLIENTE = 150;
    public static final int LIMITE_DO_CONTATO = 150;
    public static final int LIMITE_DO_MODULO = 300;
    public static final int LIMITE_DA_TELA = 200;
    public static final int LIMITE_DO_CODIGO = 40;
    public static final int LIMITE_DOS_TEXTOS = 20_000;

    public DadosDoRegistro {
        Objects.requireNonNull(tipo, "tipo");
    }

    /**
     * Confere e limpa o que a pessoa digitou: espaços das pontas, uma linha só nos campos curtos, "não identificado"
     * vira vazio. Todos os problemas saem numa mensagem só.
     *
     * @throws RegraNegocioException REGISTRO_INVALIDO
     */
    public static DadosDoRegistro conferir(String titulo, String tipo, String cliente, String contato, String modulo,
                                           String telaNome, String telaCodigo, String sintoma, String mensagemErro,
                                           String causa, String resolucao) {
        List<String> problemas = new ArrayList<>();
        String t = linha(titulo);
        if (t == null) {
            problemas.add("escreva o título");
        }
        TipoDoRegistro qual = TipoDoRegistro.doCodigo(tipo).orElse(null);
        if (qual == null) {
            problemas.add("escolha o tipo (dúvida, erro ou alteração)");
        }
        DadosDoRegistro dados = new DadosDoRegistro(t, qual == null ? TipoDoRegistro.DUVIDA : qual, linha(cliente),
                linha(contato), linha(modulo), linha(telaNome), linha(telaCodigo), texto(sintoma), texto(mensagemErro),
                texto(causa), texto(resolucao));
        limite(problemas, "o título", dados.titulo, LIMITE_DO_TITULO);
        limite(problemas, "o cliente", dados.cliente, LIMITE_DO_CLIENTE);
        limite(problemas, "o contato", dados.contato, LIMITE_DO_CONTATO);
        limite(problemas, "o módulo", dados.modulo, LIMITE_DO_MODULO);
        limite(problemas, "a tela", dados.telaNome, LIMITE_DA_TELA);
        limite(problemas, "o código da tela", dados.telaCodigo, LIMITE_DO_CODIGO);
        limite(problemas, "o sintoma", dados.sintoma, LIMITE_DOS_TEXTOS);
        limite(problemas, "a mensagem de erro", dados.mensagemErro, LIMITE_DOS_TEXTOS);
        limite(problemas, "a causa", dados.causa, LIMITE_DOS_TEXTOS);
        limite(problemas, "a resolução", dados.resolucao, LIMITE_DOS_TEXTOS);
        if (!problemas.isEmpty()) {
            String lista = String.join("; ", problemas);
            throw new RegraNegocioException("REGISTRO_INVALIDO", "Confira o registro: " + lista + ".");
        }
        return dados;
    }

    private static void limite(List<String> problemas, String campo, String valor, int limite) {
        if (valor != null && valor.length() > limite) {
            problemas.add(campo + " tem " + valor.length() + " caracteres (até " + limite + ")");
        }
    }

    /** Uma linha: espaços repetidos e quebras viram um espaço. */
    static String linha(String valor) {
        return vazio(valor == null ? null : valor.replaceAll("\\s+", " ").strip());
    }

    /** Um texto: as quebras ficam (no máximo uma linha em branco seguida). */
    static String texto(String valor) {
        if (valor == null) {
            return null;
        }
        String t = valor.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t]+\n", "\n")
                .replaceAll("\n{3,}", "\n\n").strip();
        return vazio(t);
    }

    private static String vazio(String t) {
        if (t == null || t.isEmpty()) {
            return null;
        }
        String minusculo = t.toLowerCase(Locale.ROOT).replaceAll("[.\\s]+$", "");
        return minusculo.equals("não identificado") || minusculo.equals("nao identificado")
                || minusculo.equals("não informado") ? null : t;
    }
}
