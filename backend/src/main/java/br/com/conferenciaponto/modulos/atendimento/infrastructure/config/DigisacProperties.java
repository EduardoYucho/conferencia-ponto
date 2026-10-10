package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * De onde os anexos podem ser baixados ({@code atendimento.digisac.hosts-permitidos}): o link vem de um arquivo
 * enviado pela pessoa, então só estes hosts. {@code *.dominio} vale para os subdomínios.
 */
@ConfigurationProperties(prefix = "atendimento.digisac")
public record DigisacProperties(
        @DefaultValue("*.compat.objectstorage.sa-vinhedo-1.oraclecloud.com") List<String> hostsPermitidos) {
}
