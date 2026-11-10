package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** A leitura dos painéis da SVRS, com cópias sintéticas das páginas (mesma estrutura, dados inventados). */
class LeitorDoPainelSvrsTest {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    /** 16:55 em Brasília. */
    private static final Instant AGORA = Instant.parse("2026-11-09T19:55:00Z");
    private final LeitorDoPainelSvrs leitor = new LeitorDoPainelSvrs();

    @Test
    @DisplayName("NF-e: os dois grupos (SEFAZ-RS e SVRS), a situação e o CStat de cada serviço, sem a linha de homologação")
    void painelDaNfe() throws IOException {
        LeitorDoPainelSvrs.Lido lido = leitor.ler(pagina("painel-nfe.html"), null, Documento.NFE, AGORA, BRASILIA,
                Duration.ofMinutes(30));

        assertThat(lido.erro()).isNull();
        assertThat(lido.leituras()).extracting(l -> l.alvo().chave()).containsExactly(
                "NFE:RS:Autorização", "NFE:RS:Retorno da autorização", "NFE:RS:Consulta situação", "NFE:RS:Eventos",
                "NFE:SVRS:Autorização", "NFE:SVRS:Inutilização", "NFE:SVRS:Status do serviço",
                "NFE:SVRS:Consulta cadastro");
        assertThat(lido.leituras()).allMatch(l -> l.origem() == Origem.PAINEL_SVRS);

        Leitura autorizacao = lido.leituras().get(0);
        assertThat(autorizacao.situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(autorizacao.cstat()).isEqualTo(225);
        assertThat(autorizacao.detalhe()).isEqualTo("WS com status normal");
        assertThat(autorizacao.verificadoEm()).isEqualTo(Instant.parse("2026-11-09T19:51:34Z"));

        assertThat(lido.leituras().get(3).situacao()).as("WS fora do ar").isEqualTo(Situacao.FORA);
        assertThat(lido.leituras().get(6).situacao()).as("tempo de resposta elevado").isEqualTo(Situacao.INSTAVEL);
        assertThat(lido.leituras().get(7)).as("linha sem CStat").satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(Situacao.NORMAL);
            assertThat(l.cstat()).isNull();
        });
        assertThat(lido.leituras().get(5)).as("verificado às 14:40: antigo demais").satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(Situacao.SEM_INFORMACAO);
            assertThat(l.detalhe()).isEqualTo("O portal não verifica este serviço desde 14:40 (há 135 min).");
        });
        assertThat(lido.aviso()).as("só o aviso, sem o menu que vem depois")
                .isEqualTo("Não há manutenção prevista para o próximo Domingo");
    }

    @Test
    @DisplayName("CT-e: tudo é da SVRS (o \"SEFAZ-RS\" do menu não vira grupo), nomes com prefixo e versão, página em windows-1252")
    void painelDoCte() throws IOException {
        LeitorDoPainelSvrs.Lido lido = leitor.ler(pagina("painel-cte.html"), null, Documento.CTE, AGORA, BRASILIA,
                Duration.ofMinutes(30));

        assertThat(lido.erro()).isNull();
        assertThat(lido.leituras()).extracting(l -> l.alvo().chave()).containsExactly(
                "CTE:SVRS:Autorização", "CTE:SVRS:Autorização CT-e OS", "CTE:SVRS:Eventos", "CTE:SVRS:Consulta situação",
                "CTE:SVRS:Status do serviço");
        assertThat(lido.leituras().get(2).situacao()).as("WS indisponível").isEqualTo(Situacao.FORA);
        assertThat(lido.aviso()).isEqualTo(
                "Haverá manutenção programada no ambiente de autorização do CT-e no domingo, das 8h às 12h.");
    }

    @Test
    @DisplayName("Título depois da tabela, serviço repetido no grupo e verificação de antes da meia-noite")
    void tituloDepoisEMeiaNoite() throws IOException {
        Instant meiaNoite = Instant.parse("2026-11-10T03:00:00Z"); // 00:00 de 10/11 em Brasília
        LeitorDoPainelSvrs.Lido lido = leitor.ler(pagina("painel-nfce-titulo-depois.html"), null, Documento.NFCE,
                meiaNoite, BRASILIA, Duration.ofMinutes(30));

        assertThat(lido.leituras()).extracting(l -> l.alvo().chave()).containsExactly(
                "NFCE:RS:Autorização", "NFCE:SVRS:Autorização", "NFCE:SVRS:Autorização (2)");
        assertThat(lido.leituras().get(0).verificadoEm()).isEqualTo(Instant.parse("2026-11-10T02:58:00Z"));
        assertThat(lido.leituras().get(2).situacao()).as("paralisado").isEqualTo(Situacao.FORA);
        assertThat(lido.aviso()).isNull();
    }

    @Test
    @DisplayName("Página com o formato mudado, vazia ou só com homologação: fonte com problema, nunca tudo normal")
    void formatoMudado() throws IOException {
        LeitorDoPainelSvrs.Lido mudado = leitor.ler(pagina("painel-formato-mudado.html"), null, Documento.NFE, AGORA,
                BRASILIA, Duration.ofMinutes(30));
        assertThat(mudado.leituras()).isEmpty();
        assertThat(mudado.erro()).contains("formato da página");

        assertThat(leitor.ler("", Documento.MDFE, AGORA, BRASILIA, Duration.ofMinutes(30)).erro()).isNotNull();
        assertThat(leitor.ler("<table><tr><td>WS Autorizacao HMLE</td><td>16:50:00 - WS com status normal (CStat: 1)</td></tr></table>",
                Documento.MDFE, AGORA, BRASILIA, Duration.ofMinutes(30)).erro()).isNotNull();
    }

    @Test
    @DisplayName("Aviso em maiúsculas ou dividido em pedaços; menu com a palavra manutenção não é aviso")
    void avisos() {
        String maiusculas = "<p>HAVERÁ MANUTENÇÃO NO AMBIENTE DE AUTORIZAÇÃO NO DOMINGO</p>"
                + "<table><tr><td>WS Autorizacao</td><td>16:50:00 - WS com status normal (CStat: 1)</td></tr></table>";
        assertThat(leitor.ler(maiusculas, Documento.MDFE, AGORA, BRASILIA, Duration.ofMinutes(30)).aviso())
                .isEqualTo("HAVERÁ MANUTENÇÃO NO AMBIENTE DE AUTORIZAÇÃO NO DOMINGO");
        String emPedacos = "<div>Manutenção programada para <b>15/11/2026</b>, das 8h às 12h.</div>"
                + "<table><tr><td>WS Autorizacao</td><td>16:50:00 - WS com status normal (CStat: 1)</td></tr></table>";
        assertThat(leitor.ler(emPedacos, Documento.MDFE, AGORA, BRASILIA, Duration.ofMinutes(30)).aviso())
                .isEqualTo("Manutenção programada para 15/11/2026, das 8h às 12h.");
        String soMenu = "<nav><a>Manutenção</a> <a>Serviços</a></nav>"
                + "<table><tr><td>WS Autorizacao</td><td>16:50:00 - WS com status normal (CStat: 1)</td></tr></table>";
        assertThat(leitor.ler(soMenu, Documento.MDFE, AGORA, BRASILIA, Duration.ofMinutes(30)).aviso()).isNull();
    }

    @Test
    @DisplayName("O texto da situação: normal, fora (fora do ar, indisponível, erro, paralisado) e o resto instável")
    void situacoes() {
        assertThat(LeitorDoPainelSvrs.situacao("WS com status normal")).isEqualTo(Situacao.NORMAL);
        assertThat(LeitorDoPainelSvrs.situacao("WS fora do ar")).isEqualTo(Situacao.FORA);
        assertThat(LeitorDoPainelSvrs.situacao("WS Indisponível")).isEqualTo(Situacao.FORA);
        assertThat(LeitorDoPainelSvrs.situacao("Erro na comunicação")).isEqualTo(Situacao.FORA);
        assertThat(LeitorDoPainelSvrs.situacao("Serviço paralisado sem previsão")).isEqualTo(Situacao.FORA);
        assertThat(LeitorDoPainelSvrs.situacao("WS com tempo de resposta elevado")).isEqualTo(Situacao.INSTAVEL);
        assertThat(LeitorDoPainelSvrs.servico("WS MDFeConsNaoEnc (URL)")).isEqualTo("Consulta não encerrados");
        assertThat(LeitorDoPainelSvrs.servico("WS NFeDistribuicaoDFe (URL)")).isEqualTo("Distribuição DF-e");
        assertThat(LeitorDoPainelSvrs.servico("WS Algo Novo (URL)")).isEqualTo("Algo Novo");
    }

    static byte[] pagina(String nome) throws IOException {
        try (InputStream in = LeitorDoPainelSvrsTest.class.getResourceAsStream("/fiscal/" + nome)) {
            assertThat(in).as(nome).isNotNull();
            return in.readAllBytes();
        }
    }
}
