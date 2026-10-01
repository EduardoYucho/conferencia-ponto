package br.com.conferenciaponto.infrastructure.importacao;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Configuração do monitor de comprovantes (prefixo {@code ponto.importacao-pdf}).
 *
 * @param diretorio           pasta inicial do administrador (usada uma vez, na atualização para vários
 *                            usuários, se ninguém tiver pasta). Depois cada usuário escolhe a sua em
 *                            "Minha conta". Aceita pasta de rede: {@code //servidor/Ponto}
 * @param processarExistentes ao iniciar, importa PDFs que já estavam na pasta (deduplicado por hash)
 * @param varreduraPeriodica  de quanto em quanto tempo a pasta é conferida, além dos avisos do sistema
 *                            operacional (em pasta de rede os avisos podem se perder); zero desliga
 * @param intervaloReconexao  espera entre as tentativas quando a pasta está inacessível (VPN, outro PC desligado)
 * @param estabilizacao       intervalo usado para confirmar que o tamanho do arquivo parou de mudar
 * @param tentativasLeitura   tentativas enquanto o arquivo ainda está em uso (download em andamento)
 * @param regex               padrão com os grupos nomeados {@code data} (dd/MM/yyyy) e {@code hora} (HH:mm[:ss])
 */
@ConfigurationProperties(prefix = "ponto.importacao-pdf")
public record ImportacaoPdfProperties(
        @DefaultValue("true") boolean habilitado,
        String diretorio,
        @DefaultValue("true") boolean processarExistentes,
        @DefaultValue("30s") Duration varreduraPeriodica,
        @DefaultValue("15s") Duration intervaloReconexao,
        @DefaultValue("300ms") Duration estabilizacao,
        @DefaultValue("20") int tentativasLeitura,
        @DefaultValue("500ms") Duration intervaloTentativa,
        @DefaultValue("10MB") DataSize tamanhoMaximo,
        @DefaultValue(PdfParserService.REGEX_PADRAO) String regex) {
}
