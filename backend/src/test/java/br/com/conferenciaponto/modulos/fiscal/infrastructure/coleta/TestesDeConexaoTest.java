package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
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
        SondaDeConexao.Resposta semDns = new SondaDeConexao.Resposta(Situacao.FORA, "Endereço não encontrado (DNS).",
                null, false);
        TestesDeConexao testes = new TestesDeConexao(conexoes(Documento.NFE, "SP", "sp.exemplo", Documento.NFE, "MG",
                "mg.exemplo"), new SondaFalsa(Map.of("sp.exemplo", semDns, "mg.exemplo", semDns)), 4, Duration.ofSeconds(1));

        FonteDeLeituras.Resultado r = testes.ler(AGORA);

        assertThat(r.alcancou()).isFalse();
        assertThat(r.erro()).isEqualTo(TestesDeConexao.NINGUEM_RESPONDEU);
        assertThat(r.leituras()).hasSize(2).allMatch(l -> l.situacao() == Situacao.FORA);
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
