package br.com.expedio.modulos.backup.infrastructure.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A configuração dos backups de clientes ({@code backup-clientes.*} no backup-clientes.yml; os valores desta máquina
 * vão em config/application.yml).
 *
 * <p>O backup <b>não</b> fica neste computador: cada pedaço passa pela memória e vai para o navegador de quem gerou o
 * link, que grava numa pasta escolhida no computador dele. Aqui ficam só os dados de controle (andamento, a lista dos
 * arquivos com o SHA-256, os clientes) em {@code pastaEstado}.
 *
 * @param pastaEstado        dados de controle das coletas e o cadastro de clientes (nunca o backup)
 * @param portaPublica       a porta (só em 127.0.0.1) que o túnel publica: atende só a página de coleta
 * @param enderecoPublico    endereço fixo (túnel nomeado com domínio próprio); vazio = túnel rápido do cloudflared
 * @param cloudflared        caminho do cloudflared.exe; vazio = procura no PATH e nas pastas do winget
 * @param tunelAtivo         false não abre túnel nenhum (os links só funcionam nesta rede)
 * @param validadeLinkHoras  o link vence depois disso sem contato da página (cada contato renova)
 * @param nivelZstd          nível do zstd no navegador do cliente (1 a 19)
 * @param tamanhoBlocoMib    tamanho de cada bloco lido no cliente (cada um vira um pedaço do envio)
 * @param maxParteMib        maior pedaço aceito (já compactado)
 * @param limpezaDias        tira do histórico as coletas terminadas há mais de N dias (0 = nunca)
 * @param limiteColetaGb     teto de uma coleta (compactada): passou, os pedaços são recusados (0 = sem teto)
 */
@ConfigurationProperties("backup-clientes")
public record ConfiguracaoDoBackup(
        Path pastaEstado,
        int portaPublica,
        String enderecoPublico,
        String cloudflared,
        boolean tunelAtivo,
        int validadeLinkHoras,
        int nivelZstd,
        int tamanhoBlocoMib,
        int maxParteMib,
        int limpezaDias,
        int limiteColetaGb) {

    public long limiteColetaBytes() {
        return limiteColetaGb <= 0 ? Long.MAX_VALUE : (long) limiteColetaGb * 1024 * 1024 * 1024;
    }

    public Path pastaInterna() {
        return pastaEstado;
    }

    public long maxParteBytes() {
        return (long) maxParteMib * 1024 * 1024;
    }
}
