package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.LocalDate;

/** Dia com jornada base zero para todos (nacional, estadual, municipal ou dado pela empresa). */
public record Feriado(LocalDate data, String descricao, AbrangenciaFeriado abrangencia) {

    public static final int TAMANHO_DESCRICAO = 120;

    public Feriado {
        if (data == null) {
            throw new RegraNegocioException("FERIADO_SEM_DATA", "Informe a data do feriado.");
        }
        descricao = descricao == null || descricao.isBlank() ? "Feriado" : descricao.strip();
        if (descricao.length() > TAMANHO_DESCRICAO) {
            descricao = descricao.substring(0, TAMANHO_DESCRICAO);
        }
        abrangencia = abrangencia == null ? AbrangenciaFeriado.EMPRESA : abrangencia;
    }

    public Feriado(LocalDate data, String descricao) {
        this(data, descricao, AbrangenciaFeriado.EMPRESA);
    }
}
