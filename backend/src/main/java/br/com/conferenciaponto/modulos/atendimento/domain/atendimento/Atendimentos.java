package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Os atendimentos no banco. Toda consulta é do dono: o atendimento de outra pessoa simplesmente não existe para
 * quem pergunta (nem o administrador abre o dos outros).
 */
public interface Atendimentos {

    /** Grava o atendimento e os anexos do PDF (com o link de download e a validade). */
    void salvarNovo(NovoAtendimento atendimento);

    /** Os do usuário, do mais novo para o mais antigo. */
    List<ResumoDoAtendimento> doUsuario(UUID usuarioId);

    Optional<AtendimentoGuardado> buscar(UUID id, UUID usuarioId);

    boolean existe(UUID id, UUID usuarioId);

    /** O atendimento mais recente do usuário com este número de chamado do Digisac. */
    Optional<ResumoDoAtendimento> doChamado(UUID usuarioId, String chamado);

    /** Apaga (com os arquivos, análises e saídas, em cascata). @return false se não era do usuário ou não existia */
    boolean apagar(UUID id, UUID usuarioId);

    /** Dono e situação (sem conferir o dono: uso interno da fila e da retenção). */
    Optional<EstadoDoAtendimento> estado(UUID id);

    /** @param motivo só na pausa (null limpa) */
    void mudarSituacao(UUID id, SituacaoDoAtendimento situacao, String motivo, Instant agora);

    /**
     * Muda a situação só se ela ainda é uma destas (numa única gravação): duas tarefas que terminam juntas não
     * desfazem a mudança uma da outra (o "pronto" de uma não apaga o "pausado" da outra).
     *
     * @return false se a situação já era outra (nada mudou)
     */
    boolean mudarSituacaoSe(UUID id, java.util.Set<SituacaoDoAtendimento> de, SituacaoDoAtendimento nova, String motivo,
                            Instant agora);

    /**
     * Processando → pronto ou com falhas, só se não sobrou nada a fazer: nenhuma tarefa pendente ou em execução e
     * nenhuma saída esperando a redação (numa única gravação: uma tarefa criada no mesmo instante impede).
     *
     * @return false se ainda havia o que fazer (ou a situação já era outra)
     */
    boolean finalizarSeOcioso(UUID id, SituacaoDoAtendimento nova, Instant agora);

    /** O contato do atendimento (o nome no Digisac), ou vazio se ele não existe mais. */
    Optional<String> contato(UUID id);

    /** O que a pessoa informou para os chamados do desenvolvimento. */
    CamposInformados camposInformados(UUID id);

    /** Grava o que a pessoa informou (campo vazio = não informado). */
    void salvarCampos(UUID id, CamposInformados campos, Instant agora);

    /** A linha do tempo montada para a redação (substitui a anterior). */
    void salvarLinhaDoTempo(UUID id, LinhaDoTempo linha, Instant agora);

    /** A última linha do tempo montada, se houver. */
    Optional<LinhaDoTempo> linhaDoTempo(UUID id);

    /** Atendimentos cujos arquivos já passaram do prazo e ainda estão em disco. */
    List<UUID> comArquivosVencidos(Instant agora);

    void marcarArquivosApagados(UUID id, Instant agora);

    /** Atendimentos que passaram do prazo dos textos (saem inteiros). */
    List<UUID> comTextosVencidos(Instant agora);

    /** Apaga sem conferir o dono (retenção). */
    void apagarDeVez(UUID id);

    /** Quantos estão pausados agora (disco cheio), para o aviso ao administrador. */
    int pausados();
}
