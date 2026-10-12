package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.TarefaDeAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.MaterialDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.MontadorDaLinhaDoTempo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A tarefa "linha do tempo" (sem IA): junta a conversa e o que a análise leu de cada arquivo, no lugar em que cada
 * um entrou, e guarda no atendimento. É o único material da redação.
 */
@Component
public class MontarLinhaDoTempo implements ExecutorDeTarefa {

    private static final Logger log = LoggerFactory.getLogger(MontarLinhaDoTempo.class);

    private final Atendimentos atendimentos;
    private final Analises analises;
    private final ObjectMapper json;
    private final MontadorDaLinhaDoTempo montador;
    private final Clock clock;

    public MontarLinhaDoTempo(Atendimentos atendimentos, Analises analises, ObjectMapper json,
                              ConfiguracaoDaRedacao configuracao, Clock clock) {
        this.atendimentos = atendimentos;
        this.analises = analises;
        this.json = json;
        this.montador = new MontadorDaLinhaDoTempo(configuracao.margemDaLinha());
        this.clock = clock;
    }

    @Override
    public TipoDeTarefa tipo() {
        return TipoDeTarefa.LINHA_DO_TEMPO;
    }

    @Override
    public ResultadoDaTarefa executar(Tarefa tarefa) {
        Optional<AtendimentoGuardado> talvez = atendimentos.buscar(tarefa.atendimentoId(), tarefa.usuarioId());
        if (talvez.isEmpty()) {
            return ResultadoDaTarefa.definitiva("ATENDIMENTO_NAO_ENCONTRADO", "O atendimento não existe mais.");
        }
        AtendimentoGuardado atendimento = talvez.get();
        Map<UUID, TarefaDeAnalise> tarefasDeAnalise = analises.tarefasDoAtendimento(tarefa.atendimentoId()).stream()
                .collect(Collectors.toMap(TarefaDeAnalise::arquivoId, Function.identity(), (a, b) -> a));
        List<MaterialDoArquivo> materiais = new ArrayList<>();
        for (ArquivoDoAtendimento arquivo : atendimento.arquivos()) {
            material(arquivo, tarefasDeAnalise.get(arquivo.id())).ifPresent(materiais::add);
        }
        LinhaDoTempo linha = montador.montar(atendimento.conversa().itens(), materiais, atendimento.resumo().inicio(),
                atendimento.resumo().fim(), clock.instant());
        atendimentos.salvarLinhaDoTempo(tarefa.atendimentoId(), linha, clock.instant());
        log.info("Linha do tempo do atendimento {} montada: {} itens e {} sem horário", tarefa.atendimentoId(),
                linha.itens().size(), linha.semHorario().size());
        return ResultadoDaTarefa.concluida();
    }

    /** O arquivo como entra na linha do tempo (o tirado do atendimento não entra). */
    Optional<MaterialDoArquivo> material(ArquivoDoAtendimento arquivo, TarefaDeAnalise tarefaDeAnalise) {
        SituacaoDoArquivo situacao = SituacaoDoArquivo.doCodigo(arquivo.situacao());
        if (situacao == SituacaoDoArquivo.REMOVIDO) {
            return Optional.empty();
        }
        String categoria = arquivo.categoria() == null ? CategoriaDoArquivo.OUTRO.codigo() : arquivo.categoria().codigo();
        String conteudo;
        String estado;
        String resumo = null;
        if (situacao == SituacaoDoArquivo.PRONTO) {
            Optional<JsonNode> analise = analises.maisRecente(arquivo.id()).map(this::ler);
            if (analise.isPresent()) {
                conteudo = TextoDaAnalise.texto(categoria, analise.get());
                resumo = TextoDaAnalise.resumo(categoria, analise.get());
                estado = "analisado";
            } else if (arquivo.categoria() == CategoriaDoArquivo.VIDEO) {
                conteudo = "(vídeo ainda não analisado: a análise de vídeos chega numa etapa seguinte do sistema)";
                estado = "nao_analisavel";
            } else if (arquivo.categoria() == null || arquivo.categoria() == CategoriaDoArquivo.OUTRO) {
                conteudo = "(tipo de arquivo que não é analisado)";
                estado = "nao_analisavel";
            } else if (tarefaDeAnalise != null && "falhou".equals(tarefaDeAnalise.situacao())) {
                conteudo = "(não foi possível analisar este arquivo: " + tarefaDeAnalise.erroMensagem() + ")";
                estado = "falhou";
            } else {
                conteudo = "(arquivo sem análise)";
                estado = "sem_analise";
            }
        } else if (situacao == SituacaoDoArquivo.NAO_SUPORTADO) {
            conteudo = "(arquivo não suportado" + motivo(arquivo) + ")";
            estado = "nao_suportado";
        } else {
            conteudo = "(o arquivo não foi baixado" + motivo(arquivo) + ")";
            estado = "nao_baixado";
        }
        return Optional.of(new MaterialDoArquivo(arquivo.id(), arquivo.origem(), arquivo.ordem(), arquivo.nome(), categoria,
                arquivo.mensagemOrdem(), arquivo.momento(), conteudo, estado, resumo));
    }

    private static String motivo(ArquivoDoAtendimento arquivo) {
        return arquivo.erroMensagem() == null || arquivo.erroMensagem().isBlank() ? "" : ": " + arquivo.erroMensagem();
    }

    private JsonNode ler(AnaliseGuardada analise) {
        try {
            return json.readTree(analise.resultado());
        } catch (IOException e) {
            throw new IllegalStateException("A análise guardada do arquivo " + analise.arquivoId() + " está ilegível", e);
        }
    }
}
