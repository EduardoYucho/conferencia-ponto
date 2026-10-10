package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** As análises dos arquivos (atendimento.analise) e o que a análise precisa saber dos arquivos e das tarefas. */
public interface Analises {

    Optional<ArquivoParaAnalisar> arquivo(UUID arquivoId);

    /** Todos os arquivos do atendimento, na ordem da tela. */
    List<ArquivoParaAnalisar> arquivosDoAtendimento(UUID atendimentoId);

    Optional<AnaliseGuardada> buscar(UUID arquivoId, int versaoPrompt, String modelo);

    /** A análise mais recente do arquivo, se houver. */
    Optional<AnaliseGuardada> maisRecente(UUID arquivoId);

    /**
     * Uma análise de outro arquivo do mesmo usuário com o mesmo conteúdo (sha-256), na mesma versão e modelo: o
     * mesmo print enviado duas vezes não é analisado duas vezes.
     */
    Optional<AnaliseGuardada> doMesmoConteudo(UUID usuarioId, String sha256, int versaoPrompt, String modelo,
                                              UUID outroQue);

    /** @return false se o arquivo já tinha análise nesta versão e modelo (nada muda) */
    boolean salvar(UUID arquivoId, int versaoPrompt, String modelo, String resultadoJson, Integer tokensEntrada,
                   Integer tokensSaida, Integer duracaoMs, Instant agora);

    List<ResumoDaAnalise> doAtendimento(UUID atendimentoId);

    /** A última tarefa "analisar" de cada arquivo do atendimento. */
    List<TarefaDeAnalise> tarefasDoAtendimento(UUID atendimentoId);

    /** O conteúdo do arquivo mudou ou ele saiu do atendimento: as análises dele deixam de valer. */
    int apagarDoArquivo(UUID arquivoId);

    void guardarArquivoRemoto(UUID arquivoId, String nome, Instant expiraEm);

    void esquecerArquivoRemoto(UUID arquivoId);
}
