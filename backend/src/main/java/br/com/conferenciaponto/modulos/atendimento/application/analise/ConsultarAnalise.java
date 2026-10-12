package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * A análise de um arquivo para a tela (só do dono do atendimento: de outra pessoa, 404). No vídeo, também o quadro de
 * cada passo.
 */
@Service
public class ConsultarAnalise {

    private final Analises analises;
    private final ConfiguracaoDaAnalise configuracao;
    private final ObjectMapper json;
    private final QuadrosDoVideo quadros;
    private final PastaDosAtendimentos pasta;

    /** Sem os quadros dos vídeos. */
    public ConsultarAnalise(Analises analises, ConfiguracaoDaAnalise configuracao, ObjectMapper json) {
        this(analises, configuracao, json, null, null);
    }

    @Autowired
    public ConsultarAnalise(Analises analises, ConfiguracaoDaAnalise configuracao, ObjectMapper json, QuadrosDoVideo quadros,
                            PastaDosAtendimentos pasta) {
        this.analises = analises;
        this.configuracao = configuracao;
        this.json = json;
        this.quadros = quadros;
        this.pasta = pasta;
    }

    /** A análise atual (versão do prompt e modelo de agora) ou, se ela ainda não existe, a mais recente. */
    public AnaliseView doArquivo(Usuario usuario, UUID atendimentoId, UUID arquivoId) {
        ArquivoParaAnalisar arquivo = daPessoa(usuario, atendimentoId, arquivoId);
        int versao = PromptsDeAnalise.para(arquivo.categoria()).map(PromptsDeAnalise.Prompt::versao).orElse(0);
        String modelo = configuracao.identificacao(arquivo.categoria());
        AnaliseGuardada analise = analises.buscar(arquivoId, versao, modelo)
                .or(() -> analises.maisRecente(arquivoId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("ANALISE_NAO_ENCONTRADA",
                        "Este arquivo ainda não foi analisado."));
        JsonNode resultado = ler(analise);
        List<AnaliseView.Quadro> doVideo = List.of();
        String aviso = null;
        if (arquivo.categoria() == CategoriaDoArquivo.VIDEO && quadros != null) {
            List<QuadrosDoVideo.Quadro> lista = quadros.quadrosPorPasso(atendimentoId, arquivoId, resultado);
            doVideo = lista.stream().map(q -> new AnaliseView.Quadro(q.passo().numero(), q.instanteMs(), q.instante(),
                    q.disponivel())).toList();
            aviso = avisoDosQuadros(arquivo, lista);
        }
        return new AnaliseView(arquivoId, arquivo.categoria().codigo(), analise.versaoPrompt(), analise.modelo(),
                analise.versaoPrompt() == versao && analise.modelo().equals(modelo), analise.criadaEm(),
                analise.tokensEntrada(), analise.tokensSaida(), analise.duracaoMs(), resultado, doVideo, aviso);
    }

    /** O arquivo do quadro de um passo do vídeo (só os instantes dos passos da análise; o que falta: 404). */
    public Path quadro(Usuario usuario, UUID atendimentoId, UUID arquivoId, long instanteMs) {
        ArquivoParaAnalisar arquivo = daPessoa(usuario, atendimentoId, arquivoId);
        RecursoNaoEncontradoException naoHa = new RecursoNaoEncontradoException("QUADRO_NAO_ENCONTRADO",
                "Este quadro do vídeo não existe (o ffmpeg pode não estar instalado, ou os arquivos já saíram do servidor).");
        if (arquivo.categoria() != CategoriaDoArquivo.VIDEO || quadros == null) {
            throw naoHa;
        }
        AnaliseGuardada analise = analises.maisRecente(arquivoId).orElseThrow(() -> naoHa);
        return quadros.quadros(atendimentoId, arquivoId, ler(analise)).stream()
                .filter(q -> q.instanteMs() == instanteMs && q.disponivel())
                .map(q -> pasta.localDoArquivo(q.caminho()))
                .filter(Files::isRegularFile)
                .findFirst().orElseThrow(() -> naoHa);
    }

    /** Por que há passos sem quadro (nada quando todos têm). */
    private String avisoDosQuadros(ArquivoParaAnalisar arquivo, List<QuadrosDoVideo.Quadro> lista) {
        long faltando = lista.stream().filter(q -> !q.disponivel()).count();
        if (faltando == 0) {
            return null;
        }
        if (!quadros.situacao().disponivel()) {
            return "O ffmpeg não está instalado no servidor: os passos ficam sem quadros. Para instalar: winget install "
                    + "Gyan.FFmpeg; depois, gere o texto de novo (os quadros que faltam são tirados antes da redação).";
        }
        if (!arquivo.pronto()) {
            return "O vídeo já saiu do servidor: os passos que estavam sem quadro continuam sem.";
        }
        return faltando + (faltando == 1 ? " passo ainda está sem quadro" : " passos ainda estão sem quadro")
                + ": eles são tirados de novo antes da próxima redação (se o ffmpeg falhar de novo, o passo fica sem imagem).";
    }

    private ArquivoParaAnalisar daPessoa(Usuario usuario, UUID atendimentoId, UUID arquivoId) {
        return analises.arquivo(arquivoId)
                .filter(a -> a.atendimentoId().equals(atendimentoId) && a.usuarioId().equals(usuario.id()))
                .orElseThrow(() -> new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO",
                        "Arquivo não encontrado neste atendimento. Ele pode ter sido tirado."));
    }

    private JsonNode ler(AnaliseGuardada analise) {
        try {
            return json.readTree(analise.resultado());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
