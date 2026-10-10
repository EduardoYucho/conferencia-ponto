package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Download dos anexos do Digisac ({@code atendimento.download}).
 *
 * @param paralelos     downloads ao mesmo tempo, no total
 * @param tempoConexao  espera para abrir a conexão
 * @param tempoLeitura  tempo máximo sem receber nenhum byte (a conexão parada é retomada)
 * @param tamanhoMaximo por arquivo (2 GB é o limite da Files API do Gemini)
 * @param tentativas    tentativas de cada anexo, contando a primeira
 */
@ConfigurationProperties(prefix = "atendimento.download")
public record DownloadProperties(
        @DefaultValue("4") int paralelos,
        @DefaultValue("10s") Duration tempoConexao,
        @DefaultValue("60s") Duration tempoLeitura,
        @DefaultValue("2GB") DataSize tamanhoMaximo,
        @DefaultValue("5") int tentativas) {
}
