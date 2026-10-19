package br.com.conferenciaponto.domain.acesso;

/**
 * Um ajuste só de uma pessoa, por cima dos perfis dela.
 *
 * @param liberada true = liberada a mais (mesmo sem nenhum perfil dar); false = retirada (mesmo que um perfil dê)
 */
public record AjusteDeAcesso(String permissao, boolean liberada) {

    public AjusteDeAcesso {
        if (!Permissao.codigoValido(permissao)) {
            throw new IllegalArgumentException("Código de permissão inválido: " + permissao);
        }
    }
}
