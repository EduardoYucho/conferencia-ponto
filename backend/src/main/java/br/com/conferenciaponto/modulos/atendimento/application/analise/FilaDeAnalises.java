package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ResumoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.SituacaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.TarefaDeAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quais arquivos têm (ou terão) análise e em que pé está cada uma. Uma análise vale para o arquivo, a versão do
 * prompt e o modelo: com outra versão ou outro modelo na configuração, o arquivo é analisado de novo na próxima vez
 * que a pessoa processar (as antigas ficam guardadas).
 */
@Component
public class FilaDeAnalises implements EtapaDeAnalise {

    /** Falhas que se resolvem fora do arquivo (a chave): "Processar" de novo tenta outra vez. */
    static final Set<String> FALHAS_QUE_VOLTAM = Set.of("SEM_CHAVE_GEMINI", "CHAVE_GEMINI_ILEGIVEL", "CHAVE_GEMINI_RECUSADA");
    private static final Set<String> AINDA_VAI_SER_BAIXADO = Set.of("aguardando", "baixando");

    private final Analises analises;
    private final Tarefas tarefas;
    private final ConfiguracaoDaAnalise configuracao;
    private final ChavesGemini chaves;

    public FilaDeAnalises(Analises analises, Tarefas tarefas, ConfiguracaoDaAnalise configuracao, ChavesGemini chaves) {
        this.analises = analises;
        this.tarefas = tarefas;
        this.configuracao = configuracao;
        this.chaves = chaves;
    }

    @Override
    public int pedirParaOAtendimento(UUID atendimentoId, Instant agora) {
        Map<UUID, TarefaDeAnalise> ultimas = ultimasTarefas(atendimentoId);
        Map<UUID, List<ResumoDaAnalise>> feitas = feitas(atendimentoId);
        int pedidas = 0;
        for (ArquivoParaAnalisar arquivo : analises.arquivosDoAtendimento(atendimentoId)) {
            if (!analisavel(arquivo) || temAtual(arquivo, feitas)) {
                continue;
            }
            TarefaDeAnalise ultima = ultimas.get(arquivo.id());
            if (ultima != null && (ultima.ativa()
                    || ("falhou".equals(ultima.situacao()) && !FALHAS_QUE_VOLTAM.contains(ultima.erroCodigo())))) {
                continue;
            }
            if (tarefas.criar(TipoDeTarefa.ANALISAR, atendimentoId, arquivo.id(), null, configuracao.tentativas(), agora)) {
                pedidas++;
            }
        }
        return pedidas;
    }

    @Override
    public boolean pedir(UUID atendimentoId, UUID arquivoId, Instant agora) {
        return analises.arquivo(arquivoId)
                .filter(a -> a.atendimentoId().equals(atendimentoId))
                .filter(FilaDeAnalises::analisavel)
                .filter(a -> analises.buscar(a.id(), versao(a), configuracao.identificacao(a.categoria())).isEmpty())
                .map(a -> tarefas.criar(TipoDeTarefa.ANALISAR, atendimentoId, a.id(), null, configuracao.tentativas(), agora))
                .orElse(false);
    }

    @Override
    public void esquecer(UUID arquivoId) {
        analises.apagarDoArquivo(arquivoId);
    }

    @Override
    public PanoramaDasAnalises panorama(UUID atendimentoId) {
        List<ArquivoParaAnalisar> arquivos = analises.arquivosDoAtendimento(atendimentoId);
        if (arquivos.isEmpty()) {
            return PanoramaDasAnalises.VAZIO;
        }
        Map<UUID, TarefaDeAnalise> ultimas = ultimasTarefas(atendimentoId);
        Map<UUID, List<ResumoDaAnalise>> feitas = feitas(atendimentoId);
        Map<UUID, EstadoDaAnalise> estados = new HashMap<>();
        int esperadas = 0;
        int resolvidas = 0;
        boolean comFalha = false;
        boolean faltaChave = false;
        for (ArquivoParaAnalisar arquivo : arquivos) {
            if (PromptsDeAnalise.para(arquivo.categoria()).isEmpty()) {
                continue;
            }
            boolean atual = temAtual(arquivo, feitas);
            if (!arquivo.pronto()) {
                if (atual) {
                    // o arquivo saiu do disco (retenção), mas a análise continua valendo e aparece na tela
                    estados.put(arquivo.id(), new EstadoDaAnalise(SituacaoDaAnalise.PRONTA, null, null));
                } else if (AINDA_VAI_SER_BAIXADO.contains(arquivo.situacao())) {
                    esperadas++;
                }
                continue;
            }
            esperadas++;
            EstadoDaAnalise estado = atual
                    ? new EstadoDaAnalise(SituacaoDaAnalise.PRONTA, null, null)
                    : pelaTarefa(ultimas.get(arquivo.id()));
            if (estado.situacao() == SituacaoDaAnalise.PRONTA || estado.situacao() == SituacaoDaAnalise.FALHOU) {
                resolvidas++;
            }
            if (estado.situacao() == SituacaoDaAnalise.FALHOU) {
                comFalha = true;
                faltaChave |= "SEM_CHAVE_GEMINI".equals(estado.codigo());
            }
            estados.put(arquivo.id(), estado);
        }
        long entrada = 0;
        long saida = 0;
        for (List<ResumoDaAnalise> doArquivo : feitas.values()) {
            for (ResumoDaAnalise r : doArquivo) {
                entrada += r.tokensEntrada() == null ? 0 : r.tokensEntrada();
                saida += r.tokensSaida() == null ? 0 : r.tokensSaida();
            }
        }
        boolean semChave = (faltaChave || esperadas > resolvidas) && chaves.buscar(arquivos.get(0).usuarioId()).isEmpty();
        return new PanoramaDasAnalises(estados, esperadas, resolvidas, comFalha, entrada, saida, semChave);
    }

    /** O arquivo tem conteúdo e um prompt para o tipo dele (imagem, áudio, documento ou vídeo). */
    static boolean analisavel(ArquivoParaAnalisar arquivo) {
        return arquivo.pronto() && PromptsDeAnalise.para(arquivo.categoria()).isPresent();
    }

    private static EstadoDaAnalise pelaTarefa(TarefaDeAnalise tarefa) {
        if (tarefa == null || "cancelada".equals(tarefa.situacao()) || "concluida".equals(tarefa.situacao())) {
            return new EstadoDaAnalise(SituacaoDaAnalise.PENDENTE, null, null);
        }
        return switch (tarefa.situacao()) {
            case "executando" -> new EstadoDaAnalise(SituacaoDaAnalise.ANALISANDO, tarefa.erroCodigo(), espera(tarefa));
            case "falhou" -> new EstadoDaAnalise(SituacaoDaAnalise.FALHOU, tarefa.erroCodigo(), tarefa.erroMensagem());
            case "pausada" -> new EstadoDaAnalise(SituacaoDaAnalise.NA_FILA, tarefa.erroCodigo(), tarefa.erroMensagem());
            default -> new EstadoDaAnalise(SituacaoDaAnalise.NA_FILA, tarefa.erroCodigo(), espera(tarefa));
        };
    }

    /** "Tentativa 2 de 5: Gemini indisponível..." quando a última tentativa não deu certo. */
    private static String espera(TarefaDeAnalise tarefa) {
        if (tarefa.erroMensagem() == null) {
            return null;
        }
        if ("GEMINI_LIMITE".equals(tarefa.erroCodigo())) {
            return tarefa.erroMensagem();
        }
        return "Tentativa " + tarefa.tentativas() + " de " + tarefa.maxTentativas() + " não deu certo: "
                + tarefa.erroMensagem() + " Tentando de novo.";
    }

    private boolean temAtual(ArquivoParaAnalisar arquivo, Map<UUID, List<ResumoDaAnalise>> feitas) {
        int versao = versao(arquivo);
        String modelo = configuracao.identificacao(arquivo.categoria());
        return feitas.getOrDefault(arquivo.id(), List.of()).stream()
                .anyMatch(r -> r.versaoPrompt() == versao && r.modelo().equals(modelo));
    }

    private static int versao(ArquivoParaAnalisar arquivo) {
        return PromptsDeAnalise.para(arquivo.categoria()).map(PromptsDeAnalise.Prompt::versao).orElse(0);
    }

    private Map<UUID, TarefaDeAnalise> ultimasTarefas(UUID atendimentoId) {
        return analises.tarefasDoAtendimento(atendimentoId).stream()
                .collect(Collectors.toMap(TarefaDeAnalise::arquivoId, Function.identity(), (a, b) -> a));
    }

    private Map<UUID, List<ResumoDaAnalise>> feitas(UUID atendimentoId) {
        return analises.doAtendimento(atendimentoId).stream().collect(Collectors.groupingBy(ResumoDaAnalise::arquivoId));
    }
}
