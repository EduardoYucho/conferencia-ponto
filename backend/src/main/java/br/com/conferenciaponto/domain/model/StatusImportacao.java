package br.com.conferenciaponto.domain.model;

/** Resultado do processamento de um comprovante de ponto (PDF). */
public enum StatusImportacao {
    /** Batida incluída na jornada. */
    IMPORTADO,
    /** A mesma data/hora já havia sido importada ou registrada. */
    DUPLICADO,
    /** Comprovante válido, mas a batida violou uma regra (ex.: 5ª batida, data futura). */
    REJEITADO,
    /** O PDF não contém o padrão "Comprovante de Ponto - dd/MM/yyyy HH:mm:ss". */
    INVALIDO
}
