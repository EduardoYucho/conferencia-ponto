package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.port.CodificadorSenha;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptCodificadorSenha implements CodificadorSenha {

    private final PasswordEncoder encoder;

    public BCryptCodificadorSenha(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public String codificar(String senhaAberta) {
        return encoder.encode(senhaAberta);
    }

    @Override
    public boolean confere(String senhaAberta, String hash) {
        return senhaAberta != null && hash != null && encoder.matches(senhaAberta, hash);
    }
}
