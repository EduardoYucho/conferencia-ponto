package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.AnexoLido;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Os arquivos dos atendimentos no banco. Quem chama já conferiu que o atendimento é da pessoa; aqui todo arquivo
 * é procurado junto com o atendimento dele.
 */
public interface Arquivos {

    List<ArquivoDoAtendimento> doAtendimento(UUID atendimentoId);

    Optional<ArquivoDoAtendimento> buscar(UUID atendimentoId, UUID arquivoId);

    Optional<ArquivoParaBaixar> paraBaixar(UUID arquivoId);

    /** Anexos da conversa que ainda podem ser baixados (aguardando ou com falha, com link). */
    List<ArquivoParaBaixar> aBaixar(UUID atendimentoId);

    /** Aguardando ou com falha, com link vencido em {@code agora}: viram "vencido". @return quantos */
    int vencerLinks(UUID atendimentoId, Instant agora, String mensagem);

    /**
     * Aguardando ou com falha → baixando (ou continua baixando, quando a tarefa voltou depois de o serviço parar).
     * @return false se o arquivo mudou (enviado à mão, tirado)
     */
    boolean marcarBaixando(UUID arquivoId);

    /**
     * Baixando → pronto (ou não suportado), com o conteúdo, e apaga o link. @return false se o arquivo mudou
     * enquanto baixava (o conteúdo baixado deve ser descartado)
     */
    boolean concluirDownload(UUID arquivoId, ConteudoGuardado conteudo, Instant agora, String mensagemSeNaoSuportado);

    /**
     * Registra uma falha (ou a espera por uma nova tentativa), só se o arquivo ainda estiver aguardando, baixando
     * ou com falha: o que já está pronto ou foi tirado não volta atrás.
     */
    void registrarFalha(UUID arquivoId, SituacaoDoArquivo situacao, String codigo, String mensagem);

    /** Volta para "aguardando", sem erro (antes de uma nova tentativa pedida pela pessoa). */
    void aguardar(UUID arquivoId);

    /** O conteúdo enviado à mão para um anexo da conversa: pronto, sem link e sem erro. */
    void guardarConteudo(UUID arquivoId, ConteudoGuardado conteudo, Instant agora, String mensagemSeNaoSuportado);

    /** A próxima ordem livre para a origem (1, 2, 3...). */
    int proximaOrdem(UUID atendimentoId, OrigemDoArquivo origem);

    /** @return false se a ordem já foi usada por outro envio ao mesmo tempo (quem chama tenta a seguinte) */
    boolean inserir(NovoArquivo arquivo);

    /** Arquivo enviado pela pessoa: sai do banco. */
    void apagar(UUID arquivoId);

    /** Anexo da conversa: fica registrado (faz parte da conversa), mas sem arquivo e sem link. */
    void marcarRemovido(UUID arquivoId);

    /** Todos os arquivos do atendimento ficam "removido", sem caminho e sem link (retenção). */
    void marcarTodosRemovidos(UUID atendimentoId);

    /**
     * Renova os links dos anexos que ainda faltam (aguardando, com falha ou vencidos) a partir de um PDF novo do
     * mesmo chamado. Cada anexo guardado é ligado ao do PDF novo pelo nome e pela ordem; o mesmo link de antes não
     * conta. @return quantos renovou
     */
    int renovarLinks(UUID atendimentoId, List<AnexoLido> anexosDoPdfNovo);
}
