package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;

import java.util.List;

/** Dados públicos do usuário (sem hash de senha). */
public record UsuarioResponse(String login, String nome, List<String> perfis, boolean podeEscrever) {

    public static UsuarioResponse de(Usuario u) {
        return new UsuarioResponse(u.login(), u.nome(), u.perfis().stream().map(Perfil::name).sorted().toList(),
                u.podeEscrever());
    }
}
