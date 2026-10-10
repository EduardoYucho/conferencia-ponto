package br.com.conferenciaponto.modulos.atendimento;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Gera, no próprio teste, um PDF com o layout da conversa exportada pelo Digisac (conferido em PDFs reais): título
 * "Digisac - Ticket: n", cabeçalho, "Mensagens do chamado", separadores de data e eventos centralizados, balões do
 * cliente à esquerda e do atendente/bot à direita (remetente e horário menor na primeira linha), anexos como links
 * assinados e o rodapé de propaganda em letra miúda. Tudo sintético: nenhum PDF real entra no repositório.
 */
public final class PdfDigisacDeTeste {

    public static final String CHAMADO = "20261009000001";
    public static final String CONTATO = "Loja Exemplo - Maria (+55 48 99999-0000)";
    public static final String HOST = "https://bucket.compat.objectstorage.sa-vinhedo-1.oraclecloud.com";

    private static final float LARGURA = PDRectangle.A4.getWidth();
    private static final float ALTURA = PDRectangle.A4.getHeight();
    private static final float TEXTO = 10f;
    private static final float HORA = 8.5f;
    private static final float ENTRE_LINHAS = 12.4f;
    private static final float ENTRE_BALOES = 31f;
    private static final float FIM_DA_PAGINA = 770f;
    private static final PDType1Font FONTE = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font NEGRITO = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    /** Um texto ou retângulo a desenhar numa página (y de cima para baixo). */
    private record Desenho(int pagina, Consumer<PDPageContentStream> acao) {
    }

    private record Link(int pagina, float x0, float y0, float x1, float y1, String url) {
    }

    private String titulo = "Digisac - Ticket: " + CHAMADO;
    private final List<Desenho> desenhos = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private int pagina = 0;
    private float y = 50;
    private boolean rodape = true;
    private String senhaDoUsuario;

    public static PdfDigisacDeTeste novo() {
        return new PdfDigisacDeTeste();
    }

    /** Uma conversa completa: bot com a chave do mês, transferência, texto, imagem, áudio, documento e vídeo. */
    public static PdfDigisacDeTeste exemplo() {
        return novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 08:00:00", "09/10/2026 09:30:00", "-")
                .data("09/10/2026")
                .evento("Início do chamado - Bot-Atendimento (Bot) - 09/10/2026 08:00:00")
                .cliente(CONTATO, "08:01", "Bom dia")
                .atendente("Bot", "08:01", "Olá! Seja bem-vindo(a) ao atendimento.", "*1* - Suporte",
                        "*Atenção! Informamos que chave temporária para o mês de",
                        "Outubro/2026 é: 4815162342 - Válida até o dia 31/10/2026*")
                .cliente(CONTATO, "08:02", "1")
                .evento("Chamado transferido por (Bot) - De Bot-Atendimento (Bot) para Suporte (Eduardo) - 09/10/2026 08:03:10")
                .atendente("Eduardo", "08:05", "Bom dia, Maria! Em que posso ajudar?")
                .cliente(CONTATO, "08:06", "Não consigo emitir a nota fiscal,", "aparece um erro na tela de vendas")
                .clienteComImagem(CONTATO, "08:07", "1760011620000.jpeg")
                .clienteComAudio(CONTATO, "08:08", "1760011680000.oga")
                .atendente("Eduardo", "08:10", "Pode me passar o ID e a senha do Ultra?")
                .cliente(CONTATO, "08:11", "12 345 678")
                .cliente(CONTATO, "08:11", "Senha 4821")
                .clienteComDocumento(CONTATO, "08:20", "Relatório de vendas.pdf")
                .atendenteComVideo("Eduardo", "08:40", "gravacao-tela.mp4")
                .atendente("Eduardo", "09:00", "Pronto, a nota foi emitida.")
                .cliente(CONTATO, "09:01", "Obrigada!")
                .evento("Fim do chamado - Encerrado por Eduardo - 09/10/2026 09:30:00");
    }

    public PdfDigisacDeTeste titulo(String titulo) {
        this.titulo = titulo;
        return this;
    }

    public PdfDigisacDeTeste semRodape() {
        this.rodape = false;
        return this;
    }

    public PdfDigisacDeTeste protegidoComSenha(String senha) {
        this.senhaDoUsuario = senha;
        return this;
    }

    /** Cabeçalho do PDF; sem chamar, o PDF fica sem o título "Mensagens do chamado" (layout desconhecido). */
    public PdfDigisacDeTeste cabecalho(String contato, String chamado, String inicio, String fim, String assunto) {
        texto(67, 53.6f, 9, FONTE, contato);
        texto(338.9f, 54.8f, TEXTO, FONTE, "5548999990000");
        texto(67, 64.9f, TEXTO, FONTE, "Conexão");
        texto(324, 67, TEXTO, FONTE, "Chamado número: " + chamado);
        texto(324, 79.3f, TEXTO, FONTE, "Início do chamado: " + inicio);
        texto(324, 91.9f, TEXTO, FONTE, "Término do chamado: " + fim);
        texto(22.7f, 122.7f, TEXTO, FONTE, "Assunto:");
        texto(22.7f, 135.3f, TEXTO, FONTE, assunto);
        texto(22.7f, 156.8f, TEXTO, FONTE, "Resumo:");
        texto(22.7f, 169f, TEXTO, FONTE, "-");
        centralizado(208.7f, 16, NEGRITO, "Mensagens do chamado");
        y = 258.8f;
        return this;
    }

    public PdfDigisacDeTeste data(String data) {
        caber(30);
        centralizado(y, 8, NEGRITO, data);
        y += 41;
        return this;
    }

    public PdfDigisacDeTeste evento(String texto) {
        caber(30);
        centralizado(y, TEXTO, FONTE, texto);
        y += 38;
        return this;
    }

    public PdfDigisacDeTeste cliente(String remetente, String hora, String... linhas) {
        caber(ENTRE_LINHAS * linhas.length + 20);
        cabecalhoDoCliente(remetente, hora);
        for (String linha : linhas) {
            y += ENTRE_LINHAS;
            texto(18.5f, y, TEXTO, FONTE, linha);
        }
        y += ENTRE_BALOES;
        return this;
    }

    public PdfDigisacDeTeste atendente(String remetente, String hora, String... linhas) {
        caber(ENTRE_LINHAS * linhas.length + 20);
        float largura = largura(remetente, TEXTO) + 30;
        for (String linha : linhas) {
            largura = Math.max(largura, largura(linha, TEXTO));
        }
        float x0 = Math.max(320.7f, 577.5f - largura);
        texto(x0, y, TEXTO, FONTE, remetente);
        texto(555.1f - largura(hora, HORA), y, HORA, FONTE, hora);
        for (String linha : linhas) {
            y += ENTRE_LINHAS;
            texto(x0, y, TEXTO, FONTE, linha);
        }
        y += ENTRE_BALOES;
        return this;
    }

    /** Linhas de texto sem o cabeçalho do balão (a continuação de uma mensagem longa no alto da página seguinte). */
    public PdfDigisacDeTeste continuacaoDoCliente(String... linhas) {
        for (String linha : linhas) {
            texto(18.5f, y, TEXTO, FONTE, linha);
            y += ENTRE_LINHAS;
        }
        y += ENTRE_BALOES - ENTRE_LINHAS;
        return this;
    }

    public PdfDigisacDeTeste novaPagina() {
        pagina++;
        y = 45.5f;
        return this;
    }

    /** Imagem do cliente: o link cobre a miniatura, e um segundo link (o mesmo endereço) fica sobre ela. */
    public PdfDigisacDeTeste clienteComImagem(String remetente, String hora, String nome) {
        caber(130);
        cabecalhoDoCliente(remetente, hora);
        float topo = y + 3.4f;
        retangulo(18.6f, topo, 212.4f, topo + 106, new Color(200, 200, 200));
        String url = urlAssinada(nome, "20261009T120000Z", true);
        links.add(new Link(pagina, 18.6f, topo, 212.4f, topo + 106, url));
        links.add(new Link(pagina, 18.6f, topo + 47.6f, 207.8f, topo + 57.8f, url));
        y = topo + 106 + ENTRE_BALOES;
        return this;
    }

    /** Áudio do cliente: o link fica sobre o rótulo "Áudio". */
    public PdfDigisacDeTeste clienteComAudio(String remetente, String hora, String nome) {
        caber(40);
        cabecalhoDoCliente(remetente, hora);
        y += ENTRE_LINHAS;
        texto(18.5f, y, TEXTO, FONTE, "Áudio");
        links.add(new Link(pagina, 18.6f, y - 8, 18.6f + largura("Áudio", TEXTO) + 1, y + 2.1f,
                urlAssinada(nome, "20261009T120000Z", true)));
        y += ENTRE_BALOES;
        return this;
    }

    /** Documento do cliente: o rótulo é o nome do arquivo; o nome tem acento (vem em filename*=UTF-8''...). */
    public PdfDigisacDeTeste clienteComDocumento(String remetente, String hora, String nome) {
        caber(40);
        cabecalhoDoCliente(remetente, hora);
        y += ENTRE_LINHAS;
        texto(18.5f, y, TEXTO, FONTE, nome);
        String url = HOST + "/digisac-storage/7a1c/9f2e.pdf?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261009T120000Z"
                + "&X-Amz-Expires=86400&X-Amz-Signature=0f0f&X-Amz-SignedHeaders=host"
                + "&response-content-disposition=attachment%3B%20filename%2A%3DUTF-8%27%27"
                + java.net.URLEncoder.encode(java.net.URLEncoder.encode(nome, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20"), java.nio.charset.StandardCharsets.UTF_8);
        links.add(new Link(pagina, 18.6f, y - 8, 18.6f + largura(nome, TEXTO) + 1, y + 2.1f, url));
        y += ENTRE_BALOES;
        return this;
    }

    /** Vídeo do atendente, com link sem response-content-disposition (o nome sai do caminho da URL). */
    public PdfDigisacDeTeste atendenteComVideo(String remetente, String hora, String nomeNoCaminho) {
        caber(130);
        float x0 = 382.6f;
        texto(x0, y, TEXTO, FONTE, remetente);
        texto(555.1f - largura(hora, HORA), y, HORA, FONTE, hora);
        float topo = y + 3.4f;
        retangulo(x0, topo, 577.4f, topo + 100, new Color(90, 90, 90));
        links.add(new Link(pagina, x0, topo, 577.4f, topo + 100, HOST + "/digisac-storage/7a1c/" + nomeNoCaminho
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Date=20261009T120000Z&X-Amz-Expires=86400&X-Amz-Signature=abcd"));
        y = topo + 100 + ENTRE_BALOES;
        return this;
    }

    public static String urlAssinada(String nome, String data, boolean comNome) {
        return HOST + "/digisac-storage-1/a1b2c3/" + nome.hashCode() + ".bin?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=teste%2F20261009%2Fsa-vinhedo-1%2Fs3%2Faws4_request&X-Amz-Date=" + data
                + "&X-Amz-Expires=86400&X-Amz-Signature=5ec7e7&X-Amz-SignedHeaders=host"
                + (comNome ? "&response-content-disposition=attachment%3B%20filename%3D" + nome : "");
    }

    public byte[] bytes() {
        try (PDDocument documento = new PDDocument()) {
            int paginas = Math.max(pagina + 1, 1);
            for (int i = 0; i < paginas; i++) {
                documento.addPage(new PDPage(PDRectangle.A4));
            }
            PDDocumentInformation informacoes = new PDDocumentInformation();
            informacoes.setTitle(titulo);
            informacoes.setProducer("Skia/PDF (teste)");
            documento.setDocumentInformation(informacoes);
            for (int i = 0; i < paginas; i++) {
                PDPage pagina = documento.getPage(i);
                try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
                    for (Desenho d : desenhos) {
                        if (d.pagina() == i) {
                            d.acao().accept(conteudo);
                        }
                    }
                    if (rodape) {
                        desenharRodape(conteudo);
                    }
                }
                if (rodape) {
                    pagina.getAnnotations().add(link(new Link(i, 17.7f, 780.6f, 121, 810.5f, "https://novidades.exemplo.com.br/")));
                }
                for (Link l : links) {
                    if (l.pagina() == i) {
                        pagina.getAnnotations().add(link(l));
                    }
                }
            }
            if (senhaDoUsuario != null) {
                StandardProtectionPolicy politica = new StandardProtectionPolicy("dono-" + senhaDoUsuario, senhaDoUsuario,
                        new AccessPermission());
                politica.setEncryptionKeyLength(128);
                documento.protect(politica);
            }
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            documento.save(saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ desenho

    private void cabecalhoDoCliente(String remetente, String hora) {
        String nome = remetente.replaceAll("\\s*\\(.*\\)$", "");
        String telefone = remetente.length() > nome.length() ? remetente.substring(nome.length()).strip() : "";
        texto(18.5f, y, TEXTO, FONTE, nome);
        float x = 18.5f + largura(nome, TEXTO) + 3;
        if (!telefone.isEmpty()) {
            texto(x, y, 9, FONTE, telefone);
            x += largura(telefone, 9) + 5;
        }
        texto(x, y, HORA, FONTE, hora);
    }

    private void caber(float altura) {
        if (y + altura > FIM_DA_PAGINA) {
            novaPagina();
        }
    }

    private void desenharRodape(PDPageContentStream conteudo) {
        try {
            escrever(conteudo, 21, 787.8f, 3.5f, NEGRITO, "NOVIDADES Do Digi-X para sempre...");
            escrever(conteudo, 19.7f, 797, 4f, FONTE, "Na inteligência que o Digisac traz para o seu atendimento de");
            escrever(conteudo, 19.7f, 802.1f, 4f, FONTE, "clientes todos os dias...");
            escrever(conteudo, 225, 33.7f, 4.5f, FONTE, "AVISO: manutenção programada no domingo");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void texto(float x, float yTopo, float tamanho, PDType1Font fonte, String texto) {
        int naPagina = pagina;
        desenhos.add(new Desenho(naPagina, c -> {
            try {
                escrever(c, x, yTopo, tamanho, fonte, texto);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    private void centralizado(float yTopo, float tamanho, PDType1Font fonte, String texto) {
        float largura = largura(texto, tamanho, fonte);
        texto((LARGURA - largura) / 2, yTopo, tamanho, fonte, texto);
    }

    private void retangulo(float x0, float y0, float x1, float y1, Color cor) {
        int naPagina = pagina;
        desenhos.add(new Desenho(naPagina, c -> {
            try {
                c.setNonStrokingColor(cor);
                c.addRect(x0, ALTURA - y1, x1 - x0, y1 - y0);
                c.fill();
                c.setNonStrokingColor(Color.BLACK);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
    }

    private static void escrever(PDPageContentStream c, float x, float yTopo, float tamanho, PDType1Font fonte, String texto)
            throws IOException {
        c.beginText();
        c.setFont(fonte, tamanho);
        c.newLineAtOffset(x, ALTURA - yTopo);
        c.showText(texto);
        c.endText();
    }

    private static PDAnnotationLink link(Link l) {
        PDAnnotationLink anotacao = new PDAnnotationLink();
        anotacao.setRectangle(new PDRectangle(l.x0(), ALTURA - l.y1(), l.x1() - l.x0(), l.y1() - l.y0()));
        PDActionURI acao = new PDActionURI();
        acao.setURI(l.url());
        anotacao.setAction(acao);
        return anotacao;
    }

    private static float largura(String texto, float tamanho) {
        return largura(texto, tamanho, FONTE);
    }

    private static float largura(String texto, float tamanho, PDType1Font fonte) {
        try {
            return fonte.getStringWidth(texto) / 1000 * tamanho;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
