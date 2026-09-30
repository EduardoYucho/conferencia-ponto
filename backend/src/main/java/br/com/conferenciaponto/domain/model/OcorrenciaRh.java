package br.com.conferenciaponto.domain.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Ocorrência que o relatório do RH mostra no lugar das batidas ("Feriado", "Férias", "folga aniversário"...). */
public enum OcorrenciaRh {
    FERIADO(null),
    FERIAS(TipoAusencia.FERIAS),
    FOLGA(TipoAusencia.FOLGA),
    ATESTADO(TipoAusencia.ATESTADO),
    LICENCA(TipoAusencia.LICENCA),
    /** Texto não reconhecido: aparece na divergência, mas não muda o tipo do dia. */
    OUTRA(null);

    private final TipoAusencia ausencia;

    OcorrenciaRh(TipoAusencia ausencia) {
        this.ausencia = ausencia;
    }

    /** Tipo de ausência equivalente (férias, folga...); vazio para feriado e ocorrências desconhecidas. */
    public Optional<TipoAusencia> ausencia() {
        return Optional.ofNullable(ausencia);
    }

    public static Optional<OcorrenciaRh> de(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        String t = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        if (t.contains("feriado")) {
            return Optional.of(FERIADO);
        }
        if (t.contains("ferias")) {
            return Optional.of(FERIAS);
        }
        if (t.contains("folga")) {
            return Optional.of(FOLGA);
        }
        if (t.contains("atestado")) {
            return Optional.of(ATESTADO);
        }
        if (t.contains("licen")) {
            return Optional.of(LICENCA);
        }
        return Optional.of(OUTRA);
    }
}
