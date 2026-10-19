package br.com.conferenciaponto.domain.acesso;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Uma coisa que dá para liberar: uma tela ou uma ação dela (ex.: {@code ponto.lancar}). O código é o que fica
 * gravado nos perfis e nos ajustes de cada pessoa e é a authority que o Spring Security confere nas rotas.
 *
 * @param codigo    letras minúsculas separadas por ponto, até 60 caracteres; nunca muda depois de lançado
 * @param nome      como aparece na tela (ex.: "Lançar no próprio ponto")
 * @param descricao o que a permissão libera, em uma frase
 * @param requer    outra permissão sem a qual esta não vale (ex.: curar a base requer pesquisar); null = nenhuma
 */
public record Permissao(String codigo, String nome, String descricao, String requer) {

    private static final Pattern CODIGO = Pattern.compile("[a-z]+(\\.[a-z]+)*");

    public Permissao {
        if (!codigoValido(codigo)) {
            throw new IllegalArgumentException("Código de permissão inválido: " + codigo);
        }
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Permissão sem nome: " + codigo);
        }
        if (requer != null && (!codigoValido(requer) || requer.equals(codigo))) {
            throw new IllegalArgumentException("Permissão " + codigo + " requer um código inválido: " + requer);
        }
        nome = nome.strip();
        descricao = descricao == null || descricao.isBlank() ? null : descricao.strip();
    }

    public static Permissao de(String codigo, String nome, String descricao) {
        return new Permissao(codigo, nome, descricao, null);
    }

    /** A mesma permissão, valendo só junto com {@code outra}. */
    public Permissao requer(String outra) {
        return new Permissao(codigo, nome, descricao, Objects.requireNonNull(outra, "outra"));
    }

    public static boolean codigoValido(String codigo) {
        return codigo != null && codigo.length() <= 60 && CODIGO.matcher(codigo).matches();
    }
}
