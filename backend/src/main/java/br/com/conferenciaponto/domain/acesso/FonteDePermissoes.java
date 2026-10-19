package br.com.conferenciaponto.domain.acesso;

import java.util.List;

/**
 * Um grupo de permissões, como aparece na tela de perfis (ex.: "Ponto", "Atendimentos"). O ponto declara os
 * grupos dele e cada módulo declara o seu, num bean que implementa esta interface: assim o ponto lista as
 * permissões dos módulos sem depender deles, e um módulo retirado leva as permissões junto.
 */
public interface FonteDePermissoes {

    /** O nome do grupo na tela. */
    String grupo();

    /** A posição do grupo na tela (o menor primeiro). */
    int ordem();

    /** As permissões do grupo, na ordem da tela. */
    List<Permissao> permissoes();
}
