package br.com.receptorbackup.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuração do Receptor (prefixo {@code receptor} no application.yml). */
@ConfigurationProperties("receptor")
public record Propriedades(
        Path pastaDados,
        int portaAdmin,
        int portaPublica,
        String enderecoPublico,
        String cloudflared,
        int validadeLinkHoras,
        int nivelZstd,
        int tamanhoBlocoMib,
        int maxParteMib,
        long espacoMinimoLivreMib,
        int limpezaDias,
        boolean manterUltimaPorCliente) {

    public Path pastaInterna() {
        return pastaDados.resolve(".receptor");
    }

    public long maxParteBytes() {
        return (long) maxParteMib * 1024 * 1024;
    }

    public long espacoMinimoLivreBytes() {
        return espacoMinimoLivreMib * 1024 * 1024;
    }
}
