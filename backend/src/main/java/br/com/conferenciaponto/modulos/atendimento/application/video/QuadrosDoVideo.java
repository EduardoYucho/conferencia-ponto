package br.com.conferenciaponto.modulos.atendimento.application.video;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.domain.video.InstanteDoVideo;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Os passos de um vídeo analisado e o quadro de cada um: o instante que a análise indicou ("instanteDoQuadro", ou o
 * começo do passo), tirado pelo ffmpeg e guardado ao lado dos arquivos do atendimento. Os quadros são tirados logo
 * depois da análise e, de novo, antes de cada redação (o que faltar: assim, instalar o ffmpeg depois e gerar de novo
 * resolve). Sem o ffmpeg, o vídeo vale do mesmo jeito; os passos é que ficam sem quadro, com aviso.
 */
@Component
public class QuadrosDoVideo {

    private static final Logger log = LoggerFactory.getLogger(QuadrosDoVideo.class);
    /** Um quadro a menos de meio segundo do fim pode não existir: fica um pouco antes. */
    private static final long ANTES_DO_FIM_MS = 500;
    private static final int MAXIMO_DA_LEGENDA = 160;

    public static final String AVISO_SEM_FFMPEG = "Os passos do vídeo ficaram sem quadros: o ffmpeg não está instalado no "
            + "servidor. Para instalar: winget install Gyan.FFmpeg; depois, gere o texto de novo.";

    private final PastaDosAtendimentos pasta;
    private final ExtratorQuadros extrator;

    public QuadrosDoVideo(PastaDosAtendimentos pasta, ExtratorQuadros extrator) {
        this.pasta = pasta;
        this.extrator = extrator;
    }

    /**
     * Um passo do vídeo.
     *
     * @param numero    a ordem (1, 2...)
     * @param instante  quando o passo começa (MM:SS), como veio da análise
     * @param quadroMs  o instante do quadro, em ms (null se a análise não indicou um instante que dê para ler)
     * @param legenda   a tela e o que acontece no passo, para a legenda do quadro
     */
    public record Passo(int numero, String instante, Long quadroMs, String legenda) {

        /** O quadro como aparece nos identificadores: V1@02:15. */
        public String quadro() {
            return quadroMs == null ? null : InstanteDoVideo.texto(quadroMs);
        }
    }

    /**
     * Um quadro do vídeo.
     *
     * @param caminho    relativo à pasta dos atendimentos
     * @param disponivel o arquivo já existe
     */
    public record Quadro(Passo passo, long instanteMs, String caminho, boolean disponivel) {

        public String instante() {
            return InstanteDoVideo.texto(instanteMs);
        }
    }

    /**
     * O resultado de tirar os quadros que faltavam.
     *
     * @param semFfmpeg o ffmpeg não está instalado: os que faltavam continuam faltando
     * @param semVideo  o vídeo não está mais no servidor (retenção): os que faltavam não saem mais
     */
    public record Preparo(int tirados, int jaExistiam, int falharam, boolean semFfmpeg, boolean semVideo) {

        public static final Preparo NADA = new Preparo(0, 0, 0, false, false);

        /** O aviso para a tela, ou null quando todos os quadros existem. */
        public String aviso() {
            if (semFfmpeg) {
                return AVISO_SEM_FFMPEG;
            }
            if (semVideo) {
                return "O vídeo já saiu do servidor (prazo de retenção): os passos que estavam sem quadro continuam sem.";
            }
            if (falharam > 0) {
                return falharam + (falharam == 1 ? " quadro do vídeo não pôde ser tirado" : " quadros do vídeo não puderam ser tirados")
                        + " (o ffmpeg falhou): os passos ficam sem essa imagem.";
            }
            return null;
        }
    }

    public ExtratorQuadros.Situacao situacao() {
        return extrator.situacao();
    }

    /** Os passos da análise de um vídeo (esquema video-v1), na ordem. */
    public static List<Passo> passos(JsonNode analise) {
        List<Passo> passos = new ArrayList<>();
        Long duracao = InstanteDoVideo.lerMs(texto(analise.path("duracao"))).orElse(null);
        int numero = 0;
        for (JsonNode p : analise.path("passos")) {
            numero++;
            String instante = texto(p.path("instante"));
            Long quadro = InstanteDoVideo.lerMs(texto(p.path("instanteDoQuadro")))
                    .or(() -> InstanteDoVideo.lerMs(instante)).orElse(null);
            if (quadro != null && duracao != null && duracao > 0 && quadro >= duracao) {
                quadro = Math.max(0, duracao - ANTES_DO_FIM_MS);
            }
            passos.add(new Passo(numero, instante, quadro, legenda(p)));
        }
        return passos;
    }

    /** Os quadros dos passos (um por instante: dois passos no mesmo instante dividem o quadro). */
    public List<Quadro> quadros(UUID atendimentoId, UUID arquivoId, JsonNode analise) {
        List<Quadro> quadros = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (Passo passo : passos(analise)) {
            if (passo.quadroMs() == null || !vistos.add(passo.quadroMs())) {
                continue;
            }
            String caminho = pasta.caminhoDoQuadro(atendimentoId, arquivoId, passo.quadroMs());
            quadros.add(new Quadro(passo, passo.quadroMs(), caminho, Files.isRegularFile(pasta.localDoArquivo(caminho))));
        }
        return quadros;
    }

    /** O quadro de cada passo que tem instante (dois passos no mesmo instante mostram o mesmo quadro), para a tela. */
    public List<Quadro> quadrosPorPasso(UUID atendimentoId, UUID arquivoId, JsonNode analise) {
        List<Quadro> quadros = new ArrayList<>();
        for (Passo passo : passos(analise)) {
            if (passo.quadroMs() != null) {
                String caminho = pasta.caminhoDoQuadro(atendimentoId, arquivoId, passo.quadroMs());
                quadros.add(new Quadro(passo, passo.quadroMs(), caminho, Files.isRegularFile(pasta.localDoArquivo(caminho))));
            }
        }
        return quadros;
    }

    /**
     * Tira os quadros que faltam (não falha: o que não sair fica no aviso). Para no primeiro "ffmpeg ausente".
     *
     * @param caminhoDoVideo relativo à pasta dos atendimentos (o vídeo precisa estar no servidor)
     */
    public Preparo preparar(UUID atendimentoId, UUID arquivoId, String caminhoDoVideo, JsonNode analise) {
        List<Quadro> todos = quadros(atendimentoId, arquivoId, analise);
        List<Quadro> faltando = todos.stream().filter(q -> !q.disponivel()).toList();
        int existentes = todos.size() - faltando.size();
        if (faltando.isEmpty()) {
            return new Preparo(0, existentes, 0, false, false);
        }
        Path video = caminhoDoVideo == null ? null : pasta.localDoArquivo(caminhoDoVideo);
        if (video == null || !Files.isRegularFile(video)) {
            return new Preparo(0, existentes, 0, false, true);
        }
        int tirados = 0;
        int falharam = 0;
        for (Quadro quadro : faltando) {
            try {
                extrator.extrair(video, quadro.instanteMs(), pasta.localDoArquivo(quadro.caminho()));
                tirados++;
            } catch (FalhaNoQuadro falha) {
                if (falha.ausente()) {
                    return new Preparo(tirados, existentes, falharam, true, false);
                }
                falharam++;
            }
        }
        log.info("Vídeo {}: {} quadro(s) tirado(s), {} já existiam, {} falharam", arquivoId, tirados, existentes, falharam);
        return new Preparo(tirados, existentes, falharam, false, false);
    }

    /** "tela Pedidos (VEN010): grava o pedido com desconto" (sem os "não identificado"). */
    private static String legenda(JsonNode passo) {
        JsonNode tela = passo.path("tela");
        String nome = texto(tela.path("nome"));
        String codigo = texto(tela.path("codigo"));
        String descricao = texto(passo.path("descricao"));
        String daTela = nome == null ? null : codigo != null && !nome.contains(codigo) ? "tela " + nome + " (" + codigo + ")"
                : "tela " + nome;
        String legenda = daTela != null && descricao != null ? daTela + ": " + primeiraLetraMinuscula(descricao)
                : daTela != null ? daTela : descricao;
        if (legenda == null) {
            return null;
        }
        legenda = legenda.replaceFirst("\\.$", "");
        return legenda.length() <= MAXIMO_DA_LEGENDA ? legenda : legenda.substring(0, MAXIMO_DA_LEGENDA - 1).strip() + "…";
    }

    private static String primeiraLetraMinuscula(String texto) {
        if (texto.length() < 2 || !Character.isUpperCase(texto.charAt(0)) || Character.isUpperCase(texto.charAt(1))) {
            return texto;
        }
        return Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }

    /** O texto do campo, ou null se vazio ou "não identificado". */
    static String texto(JsonNode campo) {
        if (campo == null || campo.isMissingNode() || campo.isNull()) {
            return null;
        }
        String t = (campo.isTextual() ? campo.asText() : campo.toString()).strip();
        String minusculo = t.toLowerCase(Locale.ROOT);
        return t.isEmpty() || minusculo.equals("não identificado") || minusculo.equals("nao identificado") ? null : t;
    }
}
