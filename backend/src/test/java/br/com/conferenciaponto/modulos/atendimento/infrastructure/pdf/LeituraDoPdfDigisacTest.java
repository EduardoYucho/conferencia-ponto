package br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf;

import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.AnexoLido;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ConversaLida;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Lado;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeitorConversaDigisac;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste.CHAMADO;
import static br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste.CONTATO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PDFBox + leitor em PDFs sintéticos com o layout do Digisac (gerados no teste: nenhum PDF real no repositório). */
class LeituraDoPdfDigisacTest {

    private static final Instant VALIDO_ATE = Instant.parse("2026-10-10T12:00:00Z");

    @TempDir
    Path pasta;

    private final ExtratorPdfBox extrator = new ExtratorPdfBox();
    private final LeitorConversaDigisac leitor = new LeitorConversaDigisac(ZoneId.of("America/Sao_Paulo"));

    private ConversaLida ler(PdfDigisacDeTeste pdf) {
        return leitor.ler(extrator.extrair(gravar(pdf.bytes())));
    }

    private Path gravar(byte[] conteudo) {
        try {
            return Files.write(Files.createTempFile(pasta, "conversa", ".pdf"), conteudo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ItemDaConversa> mensagens(ConversaLida conversa) {
        return conversa.itens().stream().filter(ItemDaConversa::mensagem).toList();
    }

    @Test
    void leOCabecalhoSemGuardarOTelefone() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.exemplo());

        assertThat(conversa.cabecalho().chamado()).isEqualTo(CHAMADO);
        assertThat(conversa.cabecalho().contato()).isEqualTo("Loja Exemplo - Maria");
        assertThat(conversa.cabecalho().inicio()).isEqualTo(Instant.parse("2026-10-09T11:00:00Z"));
        assertThat(conversa.cabecalho().fim()).isEqualTo(Instant.parse("2026-10-09T12:30:00Z"));
        assertThat(conversa.cabecalho().assunto()).isNull();
        assertThat(conversa.cabecalho().resumo()).isNull();
        assertThat(conversa.itens()).noneMatch(i -> i.texto().contains("99999") || String.valueOf(i.remetente()).contains("99999"));
    }

    @Test
    void leAsMensagensComLadoRemetenteEHorario() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.exemplo());
        List<ItemDaConversa> mensagens = mensagens(conversa);

        assertThat(mensagens).hasSize(14);
        assertThat(conversa.linhasSemCabecalho()).isZero();
        ItemDaConversa primeira = mensagens.get(0);
        assertThat(primeira.lado()).isEqualTo(Lado.CLIENTE);
        assertThat(primeira.remetente()).isEqualTo("Loja Exemplo - Maria");
        assertThat(primeira.momento()).isEqualTo(Instant.parse("2026-10-09T11:01:00Z"));
        assertThat(primeira.texto()).isEqualTo("Bom dia");

        ItemDaConversa bot = mensagens.get(1);
        assertThat(bot.lado()).isEqualTo(Lado.ATENDENTE);
        assertThat(bot.remetente()).isEqualTo("Bot");
        assertThat(bot.texto().split("\n")).hasSize(4).startsWith("Olá! Seja bem-vindo(a) ao atendimento.");

        assertThat(mensagens.get(3).remetente()).isEqualTo("Eduardo");
        assertThat(mensagens.get(4).texto()).isEqualTo("Não consigo emitir a nota fiscal,\naparece um erro na tela de vendas");
        assertThat(mensagens).extracting(ItemDaConversa::lado).containsExactly(Lado.CLIENTE, Lado.ATENDENTE, Lado.CLIENTE,
                Lado.ATENDENTE, Lado.CLIENTE, Lado.CLIENTE, Lado.CLIENTE, Lado.ATENDENTE, Lado.CLIENTE, Lado.CLIENTE,
                Lado.CLIENTE, Lado.ATENDENTE, Lado.ATENDENTE, Lado.CLIENTE);
    }

    @Test
    void leOsEventosNaOrdemEComOHorario() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.exemplo());
        List<ItemDaConversa> eventos = conversa.itens().stream().filter(i -> !i.mensagem()).toList();

        assertThat(eventos).hasSize(3);
        assertThat(eventos.get(0).texto()).startsWith("Início do chamado");
        assertThat(eventos.get(1).texto()).startsWith("Chamado transferido por (Bot)");
        assertThat(eventos.get(1).momento()).isEqualTo(Instant.parse("2026-10-09T11:03:10Z"));
        assertThat(eventos.get(2).texto()).startsWith("Fim do chamado");
        assertThat(conversa.itens().get(0)).isEqualTo(eventos.get(0));
        assertThat(conversa.itens().get(conversa.itens().size() - 1)).isEqualTo(eventos.get(2));
        assertThat(conversa.itens()).extracting(ItemDaConversa::ordem).containsExactly(
                conversa.itens().stream().map(ItemDaConversa::ordem).sorted().toArray(Integer[]::new));
    }

    @Test
    void ignoraORodapeEOAvisoEmLetraMiuda() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.exemplo());

        assertThat(conversa.itens()).noneMatch(i -> i.texto().contains("Digisac traz") || i.texto().contains("manutenção")
                || i.texto().contains("NOVIDADES"));
        assertThat(conversa.linksIgnorados()).isEqualTo(2); // a propaganda do rodapé, uma por página
    }

    @Test
    void leOsAnexosELigaCadaUmAMensagemEmQueFoiEnviado() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.exemplo());
        List<AnexoLido> anexos = conversa.anexos();

        assertThat(anexos).extracting(AnexoLido::categoria).containsExactly(CategoriaDoArquivo.IMAGEM, CategoriaDoArquivo.AUDIO,
                CategoriaDoArquivo.DOCUMENTO, CategoriaDoArquivo.VIDEO);
        assertThat(anexos).extracting(AnexoLido::nome)
                .containsExactly("1760011620000.jpeg", "1760011680000.oga", "Relatório de vendas.pdf", "gravacao-tela.mp4");
        assertThat(anexos).extracting(AnexoLido::tipoDeMidia).containsExactly("image/jpeg", "audio/ogg", "application/pdf", "video/mp4");
        assertThat(anexos).allMatch(a -> VALIDO_ATE.equals(a.validoAte()));
        assertThat(conversa.linksValidosAte()).isEqualTo(VALIDO_ATE);

        for (AnexoLido anexo : anexos) {
            ItemDaConversa mensagem = conversa.itens().get(anexo.mensagemOrdem() - 1);
            assertThat(mensagem.anexos()).containsExactly(anexo.ordem());
            assertThat(anexo.momento()).isEqualTo(mensagem.momento());
        }
        ItemDaConversa daImagem = conversa.itens().get(anexos.get(0).mensagemOrdem() - 1);
        assertThat(daImagem.lado()).isEqualTo(Lado.CLIENTE);
        assertThat(daImagem.momento()).isEqualTo(Instant.parse("2026-10-09T11:07:00Z"));
        assertThat(daImagem.texto()).isEmpty();
        ItemDaConversa doAudio = conversa.itens().get(anexos.get(1).mensagemOrdem() - 1);
        assertThat(doAudio.texto()).as("o rótulo \"Áudio\" não vira texto").isEmpty();
        assertThat(conversa.itens().get(anexos.get(3).mensagemOrdem() - 1).lado()).isEqualTo(Lado.ATENDENTE);
    }

    @Test
    void mensagemLongaContinuaNaPaginaSeguinte() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 10:00:00", "09/10/2026 10:30:00", "-")
                .data("09/10/2026")
                .cliente(CONTATO, "10:00", "primeira parte")
                .novaPagina()
                .continuacaoDoCliente("segunda parte")
                .atendente("Eduardo", "10:01", "certo"));

        assertThat(mensagens(conversa)).extracting(ItemDaConversa::texto).containsExactly("primeira parte\nsegunda parte", "certo");
        assertThat(conversa.linhasSemCabecalho()).isZero();
    }

    @Test
    void conversaCurtaComRodapeMaiorQueAsMensagens() {
        // duas páginas de propaganda no rodapé têm mais letras do que as mensagens: o rodapé continua de fora
        ConversaLida conversa = ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 10:00:00", "09/10/2026 10:30:00", "-")
                .data("09/10/2026")
                .cliente(CONTATO, "10:00", "oi")
                .novaPagina()
                .atendente("Eduardo", "10:01", "ok"));

        assertThat(mensagens(conversa)).extracting(ItemDaConversa::texto).containsExactly("oi", "ok");
    }

    @Test
    void mensagemEditadaTemAMarcaAntesDoHorario() {
        // como nos PDFs reais: o horário da mensagem editada vem com a marca na mesma letra menor ("editada 08:05")
        ConversaLida conversa = ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 08:00:00", "09/10/2026 08:30:00", "-")
                .data("09/10/2026")
                .cliente(CONTATO, "08:01", "Bom dia")
                .atendente("Eduardo", "editada 08:05", "Corrigi a mensagem anterior, desculpe")
                .atendente("Eduardo", "08:06", "Pode testar agora?"));

        List<ItemDaConversa> mensagens = mensagens(conversa);
        assertThat(mensagens).extracting(ItemDaConversa::remetente).containsExactly("Loja Exemplo - Maria", "Eduardo", "Eduardo");
        assertThat(mensagens.get(1).momento()).isEqualTo(Instant.parse("2026-10-09T11:05:00Z"));
        assertThat(mensagens.get(1).texto()).isEqualTo("Corrigi a mensagem anterior, desculpe");
        assertThat(conversa.linhasSemCabecalho()).isZero();
    }

    @Test
    void legendaDaImagemTerminadaEmHorarioNaoViraMensagemNova() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 10:00:00", "09/10/2026 10:30:00", "-")
                .data("09/10/2026")
                .clienteComImagem(CONTATO, "10:00", "erro.png")
                .continuacaoDoCliente("o erro apareceu às 10:30")
                .atendente("Eduardo", "10:31", "certo"));

        List<ItemDaConversa> mensagens = mensagens(conversa);
        assertThat(mensagens).extracting(ItemDaConversa::texto).containsExactly("o erro apareceu às 10:30", "certo");
        assertThat(mensagens.get(0).anexos()).containsExactly(1);
        assertThat(conversa.linhasSemCabecalho()).isZero();
    }

    @Test
    void oSeparadorDeDataMudaODiaDasMensagens() {
        ConversaLida conversa = ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 23:50:00", "10/10/2026 00:10:00", "Nota fiscal")
                .data("09/10/2026")
                .cliente(CONTATO, "23:59", "ainda está aí?")
                .data("10/10/2026")
                .atendente("Eduardo", "00:01", "sim"));

        assertThat(mensagens(conversa)).extracting(ItemDaConversa::momento)
                .containsExactly(Instant.parse("2026-10-10T02:59:00Z"), Instant.parse("2026-10-10T03:01:00Z"));
        assertThat(conversa.cabecalho().assunto()).isEqualTo("Nota fiscal");
    }

    @Test
    void tituloQueNaoEDoDigisac() {
        assertThatThrownBy(() -> ler(PdfDigisacDeTeste.exemplo().titulo("Relatório de vendas")))
                .isInstanceOf(LeituraDoPdfException.class)
                .hasFieldOrPropertyWithValue("codigo", "PDF_NAO_E_DIGISAC");
    }

    @Test
    void arquivoQueNaoEPdfOuEstaCorrompido() {
        Path texto = gravar("isto não é um PDF".getBytes(StandardCharsets.UTF_8));
        byte[] pdf = PdfDigisacDeTeste.exemplo().bytes();
        Path cortado = gravar(java.util.Arrays.copyOf(pdf, 400));

        assertThatThrownBy(() -> extrator.extrair(texto)).hasFieldOrPropertyWithValue("codigo", "PDF_INVALIDO");
        assertThatThrownBy(() -> extrator.extrair(cortado)).hasFieldOrPropertyWithValue("codigo", "PDF_INVALIDO");
    }

    @Test
    void pdfComSenha() {
        Path protegido = gravar(PdfDigisacDeTeste.exemplo().protegidoComSenha("1234").bytes());

        assertThatThrownBy(() -> extrator.extrair(protegido)).hasFieldOrPropertyWithValue("codigo", "PDF_PROTEGIDO");
    }

    @Test
    void pdfDoDigisacComOutroLayout() {
        assertThatThrownBy(() -> ler(PdfDigisacDeTeste.novo().cliente(CONTATO, "10:00", "oi").atendente("Eduardo", "10:01", "oi")))
                .hasFieldOrPropertyWithValue("codigo", "PDF_LAYOUT_DESCONHECIDO");
    }

    @Test
    void pdfDoDigisacSemMensagens() {
        assertThatThrownBy(() -> ler(PdfDigisacDeTeste.novo()
                .cabecalho(CONTATO, CHAMADO, "09/10/2026 10:00:00", "09/10/2026 10:01:00", "-")
                .data("09/10/2026")
                .evento("Início do chamado - Bot-Atendimento (Bot) - 09/10/2026 10:00:00")
                .evento("Fim do chamado - Encerrado por Eduardo - 09/10/2026 10:01:00")))
                .hasFieldOrPropertyWithValue("codigo", "PDF_SEM_MENSAGENS");
    }
}
