package br.com.conferenciaponto.infrastructure.persistence;

import java.util.List;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

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
        return jpa.findByDataBetweenOrderByData(inicio, fim).stream()
                .map(f -> new Feriado(f.getData(), f.getDescricao())).toList();
    }

    @Override
    public void cadastrar(LocalDate data, String descricao) {
        if (!jpa.existsById(data)) {
            String texto = descricao == null || descricao.isBlank() ? "Feriado" : descricao.strip();
            jpa.save(new FeriadoEntity(data, texto.length() > 120 ? texto.substring(0, 120) : texto, "EMPRESA"));
        }
    }
}
