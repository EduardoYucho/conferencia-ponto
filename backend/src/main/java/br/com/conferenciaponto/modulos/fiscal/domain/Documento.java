package br.com.conferenciaponto.modulos.fiscal.domain;

import java.util.Locale;
import java.util.Optional;

/** Os documentos fiscais acompanhados (a NFS-e entra na etapa M4). */
public enum Documento {
    NFE("NF-e"),
    NFCE("NFC-e"),
    CTE("CT-e"),
    MDFE("MDF-e");

    private final String rotulo;

    Documento(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }

    /** "nfe", "NF-e", "NFE"... */
    public static Optional<Documento> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        String limpo = codigo.strip().toUpperCase(Locale.ROOT).replace("-", "");
        for (Documento d : values()) {
            if (d.name().equals(limpo)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
