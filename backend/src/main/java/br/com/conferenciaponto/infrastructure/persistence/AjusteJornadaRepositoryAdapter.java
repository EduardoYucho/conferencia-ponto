package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class AjusteJornadaRepositoryAdapter implements AjusteJornadaRepository {

    private final AjusteJornadaJpaRepository jpa;

    AjusteJornadaRepositoryAdapter(AjusteJornadaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void salvar(AjusteJornada ajuste) {
        AjusteJornadaEntity e = new AjusteJornadaEntity(ajuste.id(), ajuste.registroJornadaId(), ajuste.data(),
                HorariosTexto.escrever(ajuste.antes()), HorariosTexto.escrever(ajuste.depois()),
                ajuste.justificativa(), ajuste.usuario(), ajuste.ajustadoEm().atOffset(ZoneOffset.UTC));
        e.setUsuarioId(ajuste.usuarioId());
        jpa.save(e);
    }

    @Override
    public List<AjusteJornada> listarPorData(UUID usuarioId, LocalDate data) {
        return jpa.findByUsuarioIdAndDataReferenciaOrderByAjustadoEmDesc(usuarioId, data).stream()
                .map(AjusteJornadaRepositoryAdapter::paraDominio)
                .toList();
    }

    @Override
    public List<AjusteJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return jpa.findByUsuarioIdAndDataReferenciaBetweenOrderByAjustadoEmDesc(usuarioId, inicio, fim).stream()
                .map(AjusteJornadaRepositoryAdapter::paraDominio)
                .toList();
    }

    private static AjusteJornada paraDominio(AjusteJornadaEntity e) {
        return new AjusteJornada(e.getId(), e.getUsuarioId(), e.getRegistroJornadaId(), e.getDataReferencia(),
                HorariosTexto.ler(e.getBatidasAntes()), HorariosTexto.ler(e.getBatidasDepois()),
                e.getJustificativa(), e.getUsuarioLogin(), e.getAjustadoEm().toInstant());
    }
}
