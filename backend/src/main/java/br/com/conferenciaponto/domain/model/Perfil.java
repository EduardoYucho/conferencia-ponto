package br.com.conferenciaponto.domain.model;

/** Perfis de acesso (RBAC). O nome é a authority usada pelo Spring Security. */
public enum Perfil {
    /** Leitura e escrita total, lançamentos manuais e processamento de arquivos. */
    ROLE_ADMIN,
    /** Mesmas permissões do ADMIN no portal. */
    ROLE_USER,
    /** Somente leitura (coordenação/auditoria). */
    ROLE_VIEWER;

    public boolean podeEscrever() {
        return this == ROLE_ADMIN || this == ROLE_USER;
    }
}
