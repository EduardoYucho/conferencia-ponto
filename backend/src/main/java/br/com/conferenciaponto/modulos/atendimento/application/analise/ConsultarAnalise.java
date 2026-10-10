package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

/** A análise de um arquivo para a tela (só do dono do atendimento: de outra pessoa, 404). */
@Service
public class ConsultarAnalise {

    private final Analises analises;
    private final ConfiguracaoDaAnalise configuracao;
    private final ObjectMapper json;

    public ConsultarAnalise(Analises analises, ConfiguracaoDaAnalise configuracao, ObjectMapper json) {
        this.analises = analises;
        this.configuracao = configuracao;
        this.json = json;
    }

    /** A análise atual (versão do prompt e modelo de agora) ou, se ela ainda não existe, a mais recente. */
    public AnaliseView doArquivo(Usuario usuario, UUID atendimentoId, UUID arquivoId) {
        ArquivoParaAnalisar arquivo = analises.arquivo(arquivoId)
                .filter(a -> a.atendimentoId().equals(atendimentoId) && a.usuarioId().equals(usuario.id()))
                .orElseThrow(() -> new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO",
                        "Arquivo não encontrado neste atendimento. Ele pode ter sido tirado."));
        int versao = PromptsDeAnalise.para(arquivo.categoria()).map(PromptsDeAnalise.Prompt::versao).orElse(0);
        String modelo = configuracao.modelo(arquivo.categoria());
        AnaliseGuardada analise = analises.buscar(arquivoId, versao, modelo)
                .or(() -> analises.maisRecente(arquivoId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("ANALISE_NAO_ENCONTRADA",
                        "Este arquivo ainda não foi analisado."));
        try {
            return new AnaliseView(arquivoId, arquivo.categoria().codigo(), analise.versaoPrompt(), analise.modelo(),
                    analise.versaoPrompt() == versao && analise.modelo().equals(modelo), analise.criadaEm(),
                    analise.tokensEntrada(), analise.tokensSaida(), analise.duracaoMs(), json.readTree(analise.resultado()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
