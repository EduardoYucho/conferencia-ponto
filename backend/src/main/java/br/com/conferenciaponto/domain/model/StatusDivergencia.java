package br.com.conferenciaponto.domain.model;

public enum StatusDivergencia {
    PENDENTE,
    /** "Aceitar dados do RH": a conferência foi alterada para ficar igual ao RH. */
    ACEITO_RH,
    /** "Manter dados locais": a diferença foi reconhecida e fica como está. */
    MANTIDO_LOCAL,
    /** A conferência passou a bater com o RH por outro caminho (ajuste manual, novo PDF...). */
    RESOLVIDA
}
