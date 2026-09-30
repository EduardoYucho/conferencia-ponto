package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Repository
class AjusteJornadaRepositoryAdapter implements AjusteJornadaRepository {

    private final AjusteJornadaJpaRepository jpa;

    AjusteJornadaRepositoryAdapter(AjusteJornadaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void salvar(AjusteJornada ajuste) {
        jpa.save(new AjusteJornadaEntity(ajuste.id(), ajuste.registroJornadaId(), ajuste.data(),
                HorariosTexto.escrever(ajuste.antes()), HorariosTexto.escrever(ajuste.depois()),
                ajuste.justificativa(), ajuste.usuario(), ajuste.ajustadoEm().atOffset(ZoneOffset.UTC)));
    }

    @Override
    public List<AjusteJornada> listarPorData(LocalDate data) {
        return jpa.findByDataReferenciaOrderByAjustadoEmDesc(data).stream()
                .map(AjusteJornadaRepositoryAdapter::paraDominio)
                .toList();
    }

    @Override
    public List<AjusteJornada> listarPorPeriodo(LocalDate inicio, LocalDate fim) {
        return jpa.findByDataReferenciaBetweenOrderByAjustadoEmDesc(inicio, fim).stream()
                .map(AjusteJornadaRepositoryAdapter::paraDominio)
                .toList();
    }

    private static AjusteJornada paraDominio(AjusteJornadaEntity e) {
        return new AjusteJornada(e.getId(), e.getRegistroJornadaId(), e.getDataReferencia(),
                HorariosTexto.ler(e.getBatidasAntes()), HorariosTexto.ler(e.getBatidasDepois()),
                e.getJustificativa(), e.getUsuarioLogin(), e.getAjustadoEm().toInstant());
    }
}
