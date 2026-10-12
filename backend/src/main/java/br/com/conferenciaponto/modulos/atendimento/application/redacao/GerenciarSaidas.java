package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ConferenciaDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tela de resultado: abrir uma saída (texto, imagens, fontes), mexer no assunto, no texto e nas imagens, copiar
 * nos três modos e baixar as imagens. Cada pessoa só vê as saídas dos próprios atendimentos (a de outra pessoa dá
 * 404). "Gerar de novo" fica no processamento, que cuida da situação do atendimento.
 */
@Service
public class GerenciarSaidas {

    private static final Logger log = LoggerFactory.getLogger(GerenciarSaidas.class);

    private final Atendimentos atendimentos;
    private final Saidas saidas;
    private final PastaDosAtendimentos pasta;
    private final ConfiguracaoDaRedacao configuracao;
    private final ObjectMapper json;

    public GerenciarSaidas(Atendimentos atendimentos, Saidas saidas, PastaDosAtendimentos pasta,
                           ConfiguracaoDaRedacao configuracao, ObjectMapper json) {
        this.atendimentos = atendimentos;
        this.saidas = saidas;
        this.pasta = pasta;
        this.configuracao = configuracao;
        this.json = json;
    }

    /** Uma imagem para abrir na tela ou ir no .zip. */
    public record ArquivoDaImagem(Path local, String tipoDeMidia, String nome) {
    }

    public List<EstadoDaSaida> listar(Usuario usuario, UUID atendimentoId) {
        dono(usuario, atendimentoId);
        return saidas.ultimas(atendimentoId).stream().map(EstadoDaSaida::de).toList();
    }

    public SaidaView detalhe(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        return view(daPessoa(usuario, atendimentoId, saidaId));
    }

    public LinhaDoTempoView linhaDoTempo(Usuario usuario, UUID atendimentoId) {
        dono(usuario, atendimentoId);
        LinhaDoTempo linha = atendimentos.linhaDoTempo(atendimentoId).orElseThrow(() -> new RecursoNaoEncontradoException(
                "SEM_LINHA_DO_TEMPO", "A linha do tempo deste atendimento ainda não foi montada (ela é montada antes da "
                + "redação)."));
        List<LinhaDoTempoView.Item> itens = new ArrayList<>();
        linha.itens().forEach(i -> itens.add(item(i, false)));
        linha.semHorario().forEach(i -> itens.add(item(i, true)));
        return new LinhaDoTempoView(linha.montadaEm(), itens);
    }

    public SaidaView salvarAssunto(Usuario usuario, UUID atendimentoId, UUID saidaId, String assunto) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        String limpo = assunto == null ? "" : assunto.strip().replaceAll("\\s+", " ");
        if (limpo.isEmpty()) {
            throw new RegraNegocioException("ASSUNTO_VAZIO", "Escreva o assunto.");
        }
        if (limpo.length() > ConferenciaDoTexto.LIMITE_DO_ASSUNTO) {
            throw new RegraNegocioException("ASSUNTO_LONGO", "O assunto tem " + limpo.length() + " caracteres; o limite é "
                    + ConferenciaDoTexto.LIMITE_DO_ASSUNTO + ".");
        }
        saidas.salvarAssunto(saida.id(), limpo);
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** O texto editado na tela: limpo no servidor e com as imagens renumeradas na ordem em que ficaram. */
    public SaidaView salvarTexto(Usuario usuario, UUID atendimentoId, UUID saidaId, String html) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        String limpo = HtmlDaSaida.limpar(html);
        if (!HtmlDaSaida.temConteudo(limpo)) {
            throw new RegraNegocioException("TEXTO_VAZIO", "O texto ficou vazio. Para voltar ao texto gerado, use "
                    + "Desfazer as mudanças.");
        }
        HtmlDaSaida.Renumerado renumerado = HtmlDaSaida.renumerar(limpo, saidas.imagens(saida.id()));
        saidas.salvarTexto(saida.id(), renumerado.html(), renumerado.ordem());
        log.info("Saída {}: texto editado ({} imagens no texto)", saida.id(),
                renumerado.ordem().stream().filter(Saidas.ImagemNumerada::incluida).count());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /**
     * As imagens do texto na ordem pedida (as que não estão na lista saem do texto; as que voltam entram no fim).
     * O servidor renumera.
     */
    public SaidaView ordenarImagens(Usuario usuario, UUID atendimentoId, UUID saidaId, List<UUID> incluidas) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        List<ImagemDaSaida> imagens = saidas.imagens(saida.id());
        Set<UUID> daSaida = new HashSet<>(imagens.stream().map(ImagemDaSaida::id).toList());
        List<UUID> pedidas = incluidas == null ? List.of() : incluidas;
        if (!daSaida.containsAll(pedidas) || new HashSet<>(pedidas).size() != pedidas.size()) {
            throw new RegraNegocioException("IMAGENS_INVALIDAS", "A lista de imagens não confere com as deste texto. "
                    + "Recarregue a tela.");
        }
        String permutado = HtmlDaSaida.permutar(saida.htmlAtual(), pedidas, imagens);
        HtmlDaSaida.Renumerado renumerado = HtmlDaSaida.renumerar(permutado, imagens);
        saidas.salvarTexto(saida.id(), renumerado.html(), renumerado.ordem());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** Volta ao texto gerado (e às imagens na numeração dele). */
    public SaidaView desfazerEdicao(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        List<ImagemDaSaida> imagens = saidas.imagens(saida.id());
        TextoRedigido texto = EstruturaDaSaida.ler(json, saida.estrutura());
        // as legendas do texto gerado (a renumeração lê do HTML original)
        HtmlDaSaida.Renumerado original = HtmlDaSaida.renumerar(saida.html(), imagens);
        saidas.salvarTexto(saida.id(), null, original.ordem());
        log.info("Saída {}: mudanças desfeitas ({} trechos)", saida.id(), texto.blocos().size());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** O texto para colar no sistema de chamados, no modo pedido. */
    public CopiaView copia(Usuario usuario, UUID atendimentoId, UUID saidaId, ModoDeCopia modo) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        List<String> avisos = new ArrayList<>();
        String html;
        if (modo == ModoDeCopia.COM_IMAGENS) {
            Map<UUID, ImagemDaSaida> imagens = new HashMap<>();
            saidas.imagens(saida.id()).forEach(i -> imagens.put(i.id(), i));
            html = HtmlDaSaida.paraCopiar(saida.htmlAtual(), id -> embutida(imagens.get(id), avisos));
        } else {
            html = HtmlDaSaida.paraCopiar(saida.htmlAtual(), null);
        }
        return new CopiaView(modo.name().toLowerCase(Locale.ROOT), html, HtmlDaSaida.textoSimples(saida.htmlAtual()),
                List.copyOf(avisos));
    }

    /** O arquivo de uma imagem da saída (para a miniatura na tela). */
    public ArquivoDaImagem imagem(Usuario usuario, UUID atendimentoId, UUID saidaId, UUID imagemId) {
        SaidaGuardada saida = daPessoa(usuario, atendimentoId, saidaId);
        ImagemDaSaida imagem = saidas.imagens(saida.id()).stream().filter(i -> i.id().equals(imagemId)).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("IMAGEM_NAO_ENCONTRADA", "Imagem não encontrada."));
        Path local = pasta.localDoArquivo(imagem.caminho());
        if (!Files.isRegularFile(local)) {
            throw imagemApagada();
        }
        return new ArquivoDaImagem(local, tipoDeMidia(imagem.caminho()), nomeNoZip(imagem));
    }

    /** As imagens do texto, na ordem, para o .zip (Imagem_01.png, Imagem_02.jpeg...). */
    public List<ArquivoDaImagem> imagensParaOZip(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        List<ArquivoDaImagem> arquivos = new ArrayList<>();
        boolean faltou = false;
        for (ImagemDaSaida imagem : saidas.imagens(saida.id())) {
            if (!imagem.incluida()) {
                continue;
            }
            Path local = pasta.localDoArquivo(imagem.caminho());
            if (Files.isRegularFile(local)) {
                arquivos.add(new ArquivoDaImagem(local, tipoDeMidia(imagem.caminho()), nomeNoZip(imagem)));
            } else {
                faltou = true;
            }
        }
        if (arquivos.isEmpty()) {
            if (faltou) {
                throw imagemApagada();
            }
            throw new RegraNegocioException("SEM_IMAGENS", "Este texto não tem imagens.");
        }
        return arquivos;
    }

    // --------------------------------------------------------------------------------------------- apoio

    private SaidaView view(SaidaGuardada saida) {
        TextoRedigido texto = EstruturaDaSaida.ler(json, saida.estrutura());
        List<SaidaView.Imagem> imagens = saidas.imagens(saida.id()).stream()
                .map(i -> new SaidaView.Imagem(i.id(), i.numero(), i.legenda(), i.incluida(),
                        Files.isRegularFile(pasta.localDoArquivo(i.caminho())), nomeNoZip(i)))
                .toList();
        boolean apagadas = !imagens.isEmpty() && imagens.stream().noneMatch(SaidaView.Imagem::disponivel);
        return new SaidaView(saida.id(), saida.tipo().codigo(), saida.tipo().rotulo(), saida.versao(),
                saida.situacao().codigo(), saida.erroMensagem(), saida.assunto(), ConferenciaDoTexto.LIMITE_DO_ASSUNTO,
                saida.htmlAtual(), saida.htmlEditado() != null, texto.avisos(), texto.base(), texto.telaFuncionalidade(),
                imagens, saida.modelo(), saida.versaoPrompt(), saida.tokensEntrada(), saida.tokensSaida(), saida.criadaEm(),
                apagadas);
    }

    private Optional<String> embutida(ImagemDaSaida imagem, List<String> avisos) {
        if (imagem == null) {
            return Optional.empty();
        }
        Path local = pasta.localDoArquivo(imagem.caminho());
        try {
            if (!Files.isRegularFile(local)) {
                avisos.add("A imagem " + imagem.numero() + " não está mais no servidor (prazo de retenção) e foi só a legenda.");
                return Optional.empty();
            }
            long tamanho = Files.size(local);
            if (tamanho > configuracao.imagemMaximaNaCopia()) {
                avisos.add("A imagem " + imagem.numero() + " é grande demais para ir na cópia (" + (tamanho / (1024 * 1024))
                        + " MB): anexe o arquivo " + nomeNoZip(imagem) + ".");
                return Optional.empty();
            }
            return Optional.of("data:" + tipoDeMidia(imagem.caminho()) + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(local)));
        } catch (IOException e) {
            log.warn("Não foi possível ler a imagem {} da saída {}: {}", imagem.id(), imagem.saidaId(), e.toString());
            avisos.add("A imagem " + imagem.numero() + " não pôde ser lida e foi só a legenda.");
            return Optional.empty();
        }
    }

    private SaidaGuardada daPessoa(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        dono(usuario, atendimentoId);
        return saidas.buscar(saidaId)
                .filter(s -> s.atendimentoId().equals(atendimentoId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("SAIDA_NAO_ENCONTRADA", "Texto não encontrado. Ele "
                        + "pode ter sido apagado junto com o atendimento."));
    }

    private static SaidaGuardada comTexto(SaidaGuardada saida) {
        if (saida.situacao() != SituacaoDaSaida.PRONTA) {
            throw new RegraNegocioException("SAIDA_SEM_TEXTO", saida.situacao().emAndamento()
                    ? "O texto ainda está sendo gerado. Aguarde terminar."
                    : "Este texto não ficou pronto. Clique em Gerar de novo.");
        }
        return saida;
    }

    private void dono(Usuario usuario, UUID atendimentoId) {
        if (!atendimentos.existe(atendimentoId, usuario.id())) {
            throw new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado. Ele pode ter "
                    + "sido apagado.");
        }
    }

    private static LinhaDoTempoView.Item item(ItemDaLinha i, boolean semHorario) {
        return new LinhaDoTempoView.Item(i.id(), i.tipo().codigo(), i.momento(), i.lado(), i.remetente(), i.texto(),
                i.arquivoId(), i.origem(), i.categoria(), i.nome(), i.mensagem(), i.situacao(), semHorario);
    }

    private static RegraNegocioException imagemApagada() {
        return new RegraNegocioException("IMAGEM_APAGADA", "Os arquivos deste atendimento já foram apagados (prazo de "
                + "retenção). As imagens não estão mais no servidor.");
    }

    /** Imagem_01.png: o número com dois dígitos e a extensão do arquivo original. */
    static String nomeNoZip(ImagemDaSaida imagem) {
        return String.format(Locale.ROOT, "Imagem_%02d.%s", imagem.numero(), extensao(imagem.caminho()));
    }

    private static String extensao(String caminho) {
        String nome = caminho == null ? "" : Path.of(caminho).getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String ext = ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? ext : "img";
    }

    static String tipoDeMidia(String caminho) {
        return switch (extensao(caminho)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> "application/octet-stream";
        };
    }

    /** Escreve o .zip (quem chama já conferiu tudo; aqui só copia os bytes). */
    public static void escreverZip(List<ArquivoDaImagem> imagens, OutputStream saida) {
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (ArquivoDaImagem imagem : imagens) {
                zip.putNextEntry(new ZipEntry(imagem.nome()));
                Files.copy(imagem.local(), zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
