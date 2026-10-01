package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.port.NotificacaoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class NotificacaoRepositoryAdapter implements NotificacaoRepository {

    private final NotificacaoJpaRepository jpa;

    NotificacaoRepositoryAdapter(NotificacaoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean existeChave(UUID usuarioId, String chave) {
        return jpa.existsByUsuarioIdAndChave(usuarioId, chave);
    }

    @Override
    public void salvar(Notificacao n) {
        NotificacaoEntity e = new NotificacaoEntity(n.id(), n.tipo().name(), n.chave(), n.titulo(), n.mensagem(),
                n.link(), n.criadaEm().atOffset(ZoneOffset.UTC),
                n.lidaEm() == null ? null : n.lidaEm().atOffset(ZoneOffset.UTC));
        e.setUsuarioId(n.usuarioId());
        jpa.saveAndFlush(e);
    }

    @Override
    public List<Notificacao> listarRecentes(UUID usuarioId, int limite) {
        return jpa.findByUsuarioIdOrderByCriadaEmDesc(usuarioId, PageRequest.of(0, limite)).stream()
                .map(e -> new Notificacao(e.getId(), e.getUsuarioId(), TipoNotificacao.valueOf(e.getTipo()), e.getChave(), e.getTitulo(),
                        e.getMensagem(), e.getLink(), e.getCriadaEm().toInstant(),
                        e.getLidaEm() == null ? null : e.getLidaEm().toInstant()))
                .toList();
    }

    @Override
    public int contarNaoLidas(UUID usuarioId) {
        return (int) jpa.countByUsuarioIdAndLidaEmIsNull(usuarioId);
    }

    @Override
    public boolean marcarLida(UUID usuarioId, UUID id, Instant quando) {
        if (!jpa.existsByIdAndUsuarioId(id, usuarioId)) {
            return false;
        }
        jpa.marcarLida(id, quando.atOffset(ZoneOffset.UTC));
        return true;
    }

    @Override
    public int marcarTodasLidas(UUID usuarioId, Instant quando) {
        return jpa.marcarTodasLidas(usuarioId, quando.atOffset(ZoneOffset.UTC));
    }

    @Override
    public int marcarLidosAvisosDeCicloExceto(UUID usuarioId, String sufixoChave, Instant quando) {
        return jpa.marcarLidosAvisosDeCicloExceto(usuarioId, "%" + sufixoChave, quando.atOffset(ZoneOffset.UTC));
    }
}
