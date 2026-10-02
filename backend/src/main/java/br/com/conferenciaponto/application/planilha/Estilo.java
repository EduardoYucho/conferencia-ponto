package br.com.conferenciaponto.application.planilha;

/**
 * Aparência de uma célula, igual no Excel e no Google Sheets.
 *
 * @param tamanho  tamanho da fonte em pontos (0 = o padrão da planilha)
 * @param quebra   quebra o texto dentro da célula (coluna de observações)
 * @param bordaInferior linha embaixo da célula (separa os totais dos dias)
 */
public record Estilo(boolean negrito, boolean italico, int tamanho, Cor texto, Cor fundo, Alinhamento alinhamento,
                     boolean quebra, boolean bordaInferior) {

    public enum Alinhamento { PADRAO, ESQUERDA, CENTRO, DIREITA }

    /** Paleta curta, a mesma das telas: tinta, papel e as cores do saldo (crédito, débito, pendência). */
    public enum Cor {
        TINTA("1D1C1A"),
        SUAVE("5F5A51"),
        BRANCO("FFFFFF"),
        DEBITO("C2362F"),
        CREDITO("2D6A4C"),
        PENDENTE("B45309"),
        FUNDO_CABECALHO("1D1C1A"),
        FUNDO_TOTAL("E8E0CE"),
        FUNDO_APAGADO("F4EFE4");

        private final String hex;

        Cor(String hex) {
            this.hex = hex;
        }

        /** RRGGBB, sem o "#". */
        public String hex() {
            return hex;
        }

        public double vermelho() {
            return Integer.parseInt(hex.substring(0, 2), 16) / 255.0;
        }

        public double verde() {
            return Integer.parseInt(hex.substring(2, 4), 16) / 255.0;
        }

        public double azul() {
            return Integer.parseInt(hex.substring(4, 6), 16) / 255.0;
        }
    }

    public static final Estilo NORMAL = new Estilo(false, false, 0, null, null, Alinhamento.PADRAO, false, false);
    public static final Estilo CENTRO = NORMAL.alinhado(Alinhamento.CENTRO);
    public static final Estilo TITULO = NORMAL.emNegrito().comTamanho(14);
    public static final Estilo SUBTITULO = NORMAL.comTexto(Cor.SUAVE);
    public static final Estilo SECAO = NORMAL.emNegrito().comTamanho(12);
    public static final Estilo CABECALHO = NORMAL.emNegrito().comTexto(Cor.BRANCO).comFundo(Cor.FUNDO_CABECALHO)
            .alinhado(Alinhamento.CENTRO).comQuebra();
    public static final Estilo TOTAL = NORMAL.emNegrito().comFundo(Cor.FUNDO_TOTAL).comBordaInferior();
    public static final Estilo ROTULO = NORMAL.emNegrito();

    public Estilo emNegrito() {
        return new Estilo(true, italico, tamanho, texto, fundo, alinhamento, quebra, bordaInferior);
    }

    public Estilo emItalico() {
        return new Estilo(negrito, true, tamanho, texto, fundo, alinhamento, quebra, bordaInferior);
    }

    public Estilo comTamanho(int pontos) {
        return new Estilo(negrito, italico, pontos, texto, fundo, alinhamento, quebra, bordaInferior);
    }

    public Estilo comTexto(Cor cor) {
        return new Estilo(negrito, italico, tamanho, cor, fundo, alinhamento, quebra, bordaInferior);
    }

    public Estilo comFundo(Cor cor) {
        return new Estilo(negrito, italico, tamanho, texto, cor, alinhamento, quebra, bordaInferior);
    }

    public Estilo alinhado(Alinhamento novo) {
        return new Estilo(negrito, italico, tamanho, texto, fundo, novo, quebra, bordaInferior);
    }

    public Estilo comQuebra() {
        return new Estilo(negrito, italico, tamanho, texto, fundo, alinhamento, true, bordaInferior);
    }

    public Estilo comBordaInferior() {
        return new Estilo(negrito, italico, tamanho, texto, fundo, alinhamento, quebra, true);
    }

    /** Verde para crédito, vermelho para débito; zero fica na cor do texto. */
    public Estilo corDoSaldo(long segundos) {
        return segundos > 0 ? comTexto(Cor.CREDITO) : segundos < 0 ? comTexto(Cor.DEBITO) : this;
    }
}
