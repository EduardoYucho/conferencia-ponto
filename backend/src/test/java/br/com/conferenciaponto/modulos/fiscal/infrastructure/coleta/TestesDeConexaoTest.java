package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
import br.com.conferenciaponto.modulos.fiscal.domain.ProblemaLocal;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestesDeConexaoTest {

    private static final Instant AGORA = Instant.parse("2026-11-09T19:55:00Z");

    /** Responde pelo nome do endereço e conta quantas vezes cada um foi testado. */
    static final class SondaFalsa extends SondaDeConexao {
        final Map<String, AtomicInteger> testes = new ConcurrentHashMap<>();
        final Map<String, Resposta> respostas;

        SondaFalsa(Map<String, Resposta> respostas) {
            super(Duration.ofSeconds(1), Duration.ofSeconds(1));
            this.respostas = respostas;
        }

        @Override
        public Resposta testar(String endereco) {
            testes.computeIfAbsent(endereco, e -> new AtomicInteger()).incrementAndGet();
            if (endereco.startsWith("demora")) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return respostas.getOrDefault(endereco, new Resposta(Situacao.NORMAL, "ok", 120, true));
        }
    }

    private static Map<Documento, Map<String, String>> conexoes(Object... pares) {
        Map<Documento, Map<String, String>> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 3) {
            mapa.computeIfAbsent((Documento) pares[i], d -> new LinkedHashMap<>()).put((String) pares[i + 1],
                    (String) pares[i + 2]);
        }
        return mapa;
    }

    @Test
    @DisplayName("Uma leitura \"Conexão\" por autorizador; o mesmo endereço em dois documentos é testado uma vez só")
    void leiturasPorAutorizador() {
        SondaFalsa sonda = new SondaFalsa(Map.of("mg.exemplo", new SondaDeConexao.Resposta(Situacao.FORA,
                "Conexão recusada pelo servidor.", null, true)));
        TestesDeConexao testes = new TestesDeConexao(conexoes(
                Documento.NFE, "sp", "sp.exemplo", Documento.CTE, "SP", "SP.exemplo", Documento.CTE, "SVSP", "sp.exemplo",
                Documento.NFE, "MG", "mg.exemplo"), sonda, 4, Duration.ofSeconds(1));

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(r.alcancou()).isTrue();
        assertThat(r.erro()).isNull();
        assertThat(r.leituras()).extracting(l -> l.alvo().chave()).containsExactly(
                "NFE:SP:Conexão", "NFE:MG:Conexão", "CTE:SP:Conexão", "CTE:SVSP:Conexão");
        assertThat(r.leituras()).allMatch(l -> l.origem() == Origem.TESTE_DE_CONEXAO && AGORA.equals(l.verificadoEm()));
        Leitura mg = r.leituras().get(1);
        assertThat(mg.situacao()).isEqualTo(Situacao.FORA);
        assertThat(r.leituras().get(0).tempoMs()).isEqualTo(120);
        assertThat(sonda.testes.values().stream().mapToInt(AtomicInteger::get).sum()).as("sp.exemplo uma vez só")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Ninguém respondeu: a fonte não alcançou ninguém (pode ser a internet deste computador)")
    void ninguemRespondeu() {
        SondaDeConexao.Resposta semDns = new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, ProblemaLocal.DNS,
                null, false);
        TestesDeConexao testes = new TestesDeConexao(conexoes(Documento.NFE, "SP", "sp.exemplo", Documento.NFE, "MG",
                "mg.exemplo"), new SondaFalsa(Map.of("sp.exemplo", semDns, "mg.exemplo", semDns)), 4, Duration.ofSeconds(1));

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(r.alcancou()).isFalse();
        assertThat(r.erro()).isEqualTo(TestesDeConexao.NINGUEM_RESPONDEU);
        assertThat(r.leituras()).hasSize(2).allMatch(l -> l.situacao() == Situacao.SEM_INFORMACAO);
    }

    @Test
    @DisplayName("Muitos servidores sem resposta na mesma leitura: a internet deste computador oscilou; ficam sem informação, não fora")
    void redeDesteComputadorOscilando() {
        SondaDeConexao.Resposta semResposta = new SondaDeConexao.Resposta(Situacao.FORA, "Sem resposta em 15 s (conexão).",
                null, false);
        SondaDeConexao.Resposta semDns = new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, ProblemaLocal.DNS, null, false);
        TestesDeConexao testes = new TestesDeConexao(conexoes(Documento.NFE, "SP", "sp.exemplo", Documento.NFE, "MG",
                "mg.exemplo", Documento.NFE, "PR", "pr.exemplo", Documento.NFE, "BA", "ba.exemplo", Documento.NFE, "GO",
                "go.exemplo"), new SondaFalsa(Map.of("sp.exemplo", semResposta, "mg.exemplo", semDns, "pr.exemplo",
                semResposta)), 4, Duration.ofSeconds(1));

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(r.alcancou()).isTrue();
        assertThat(r.erro()).isEqualTo("Muitos servidores sem resposta ao mesmo tempo (3 de 5): provavelmente a internet deste computador.");
        assertThat(r.leituras()).filteredOn(l -> l.situacao() == Situacao.SEM_INFORMACAO).hasSize(3)
                .allMatch(l -> ProblemaLocal.REDE_OSCILANDO.equals(l.detalhe()));
        assertThat(r.leituras()).filteredOn(l -> l.situacao() == Situacao.NORMAL).hasSize(2);
    }

    @Test
    @DisplayName("Um ou dois servidores fora entre muitos: é deles, fica fora")
    void poucosForaContinuamFora() {
        SondaDeConexao.Resposta recusada = new SondaDeConexao.Resposta(Situacao.FORA, "Conexão recusada pelo servidor.",
                null, true);
        Object[] pares = new Object[3 * 10];
        for (int i = 0; i < 10; i++) {
            pares[3 * i] = Documento.NFE;
            pares[3 * i + 1] = "A" + i;
            pares[3 * i + 2] = "a" + i + ".exemplo";
        }
        TestesDeConexao testes = new TestesDeConexao(conexoes(pares),
                new SondaFalsa(Map.of("a0.exemplo", recusada, "a1.exemplo", recusada)), 4, Duration.ofSeconds(1));

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(r.erro()).isNull();
        assertThat(r.leituras()).filteredOn(l -> l.situacao() == Situacao.FORA).hasSize(2);
    }

    @Test
    @DisplayName("Um teste que não volta a tempo fica sem informação e não segura os outros")
    void testeQueNaoVolta() {
        TestesDeConexao testes = new TestesDeConexao(conexoes(Documento.NFE, "SP", "demora.exemplo", Documento.NFE, "MG",
                "mg.exemplo"), new SondaFalsa(Map.of()), 4, Duration.ofMillis(300));
        long inicio = System.nanoTime();

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
        assertThat(r.leituras().get(0).situacao()).isEqualTo(Situacao.SEM_INFORMACAO);
        assertThat(r.leituras().get(1).situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(r.alcancou()).isTrue();
    }
}
