package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.AbrangenciaFeriado;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
class CalendarioFeriadosAdapter implements CalendarioFeriados {

    private final FeriadoJpaRepository jpa;

    CalendarioFeriadosAdapter(FeriadoJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean isFeriado(LocalDate data) {
        return jpa.existsById(data);
    }

    @Override
    public List<Feriado> listar(LocalDate inicio, LocalDate fim) {
        return jpa.findByDataBetweenOrderByData(inicio, fim).stream().map(CalendarioFeriadosAdapter::paraDominio).toList();
    }

    @Override
    public Optional<Feriado> buscar(LocalDate data) {
        return jpa.findById(data).map(CalendarioFeriadosAdapter::paraDominio);
    }

    @Override
    public void cadastrar(LocalDate data, String descricao) {
        if (!jpa.existsById(data)) {
            salvar(new Feriado(data, descricao, AbrangenciaFeriado.EMPRESA));
        }
    }

    @Override
    public void salvar(Feriado feriado) {
        jpa.save(new FeriadoEntity(feriado.data(), feriado.descricao(), feriado.abrangencia().name()));
    }

    @Override
    public void excluir(LocalDate data) {
        jpa.deleteById(data);
    }

    private static Feriado paraDominio(FeriadoEntity e) {
        AbrangenciaFeriado abrangencia;
        try {
            abrangencia = AbrangenciaFeriado.valueOf(e.getAbrangencia());
        } catch (IllegalArgumentException | NullPointerException ex) {
            abrangencia = AbrangenciaFeriado.EMPRESA;
        }
        return new Feriado(e.getData(), e.getDescricao(), abrangencia);
    }
}
