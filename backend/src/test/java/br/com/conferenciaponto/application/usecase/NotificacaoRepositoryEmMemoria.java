package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.port.NotificacaoRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

class NotificacaoRepositoryEmMemoria implements NotificacaoRepository {

    private final List<Notificacao> notificacoes = new ArrayList<>();

    @Override
    public boolean existeChave(UUID usuarioId, String chave) {
        return notificacoes.stream().anyMatch(n -> n.usuarioId().equals(usuarioId) && n.chave().equals(chave));
    }

    @Override
    public void salvar(Notificacao notificacao) {
        notificacoes.add(notificacao);
    }

    @Override
    public List<Notificacao> listarRecentes(UUID usuarioId, int limite) {
        return notificacoes.stream().filter(n -> n.usuarioId().equals(usuarioId)).sorted(Comparator.comparing(Notificacao::criadaEm).reversed()).limit(limite).toList();
    }

    @Override
    public int contarNaoLidas(UUID usuarioId) {
        return (int) notificacoes.stream().filter(n -> n.usuarioId().equals(usuarioId) && !n.isLida()).count();
    }

    @Override
    public boolean marcarLida(UUID usuarioId, UUID id, Instant quando) {
        for (int i = 0; i < notificacoes.size(); i++) {
            Notificacao n = notificacoes.get(i);
            if (n.id().equals(id) && n.usuarioId().equals(usuarioId)) {
                notificacoes.set(i, new Notificacao(n.id(), n.usuarioId(), n.tipo(), n.chave(), n.titulo(), n.mensagem(), n.link(),
                        n.criadaEm(), n.lidaEm() != null ? n.lidaEm() : quando));
                return true;
            }
        }
        return false;
    }

    @Override
    public int marcarLidosAvisosDeCicloExceto(UUID usuarioId, String sufixoChave, Instant quando) {
        int total = 0;
        for (Notificacao n : List.copyOf(notificacoes)) {
            if (!n.isLida() && n.tipo().name().startsWith("CICLO_") && !n.chave().endsWith(sufixoChave)
                    && marcarLida(usuarioId, n.id(), quando)) {
                total++;
            }
        }
        return total;
    }

    @Override
    public int marcarTodasLidas(UUID usuarioId, Instant quando) {
        int total = 0;
        for (Notificacao n : List.copyOf(notificacoes)) {
            if (!n.isLida() && marcarLida(usuarioId, n.id(), quando)) {
                total++;
            }
        }
        return total;
    }
}
