package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

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
}
