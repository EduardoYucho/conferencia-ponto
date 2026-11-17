package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.EstadoAtual;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import br.com.conferenciaponto.modulos.fiscal.infrastructure.persistence.RegistroDeTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.conexao;
import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.servico;
import static org.assertj.core.api.Assertions.assertThat;

/** Rodadas completas, com fontes falsas e o registro no PostgreSQL de teste. */
class ColetarSituacaoTest {

    private static final ConfiguracaoDoMonitor CONFIGURACAO = new ConfiguracaoDoMonitor(
            new ConfiguracaoDoMonitor.Coleta(true, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofMillis(500),
                    Duration.ofSeconds(3), Duration.ofMinutes(30)),
            new ConfiguracaoDoMonitor.Retencao(90, 90, 7), Map.of(), Map.of(), Map.of(), Map.of());

    /** Um relógio que o teste avança. */
    static final class Relogio extends Clock {
        Instant agora = Instant.parse("2026-11-09T13:00:00Z");

        void avancar(int minutos) {
            agora = agora.plus(Duration.ofMinutes(minutos));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    /** Uma fonte que responde o que o teste mandar. */
    static final class FonteFalsa implements FonteDeLeituras {
        private final String nome;
        Function<Instant, Resultado> resposta;

        FonteFalsa(String nome) {
            this.nome = nome;
        }

        @Override
        public String nome() {
            return nome;
        }

        @Override
        public Resultado ler(Instant agora) {
            return resposta.apply(agora);
        }
    }

    private final Relogio relogio = new Relogio();
    private final FonteFalsa painel = new FonteFalsa("painel-svrs-nfe");
    private final FonteFalsa conexoes = new FonteFalsa("testes-de-conexao");
    private RegistroDoMonitor registro;
    private ColetarSituacao coleta;

    @BeforeEach
    void preparar() {
        registro = RegistroDeTeste.limpo();
        coleta = new ColetarSituacao(List.of(painel, conexoes), registro, CONFIGURACAO, relogio);
        conexoes.resposta = agora -> new FonteDeLeituras.Resultado("testes-de-conexao", true, null,
                List.of(conexao(Documento.NFE, "SP", Situacao.NORMAL, 180)), null, null);
    }

    @AfterEach
    void parar() {
        coleta.parar();
    }

    private void painelCom(Leitura... leituras) {
        painel.resposta = agora -> new FonteDeLeituras.Resultado("painel-svrs-nfe", true, null, List.of(leituras),
                Documento.NFE, "Não há manutenção prevista para o próximo Domingo.");
    }

    private EstadoAtual estado(String chave) {
        return registro.estados().get(chave);
    }

    @Test
    @DisplayName("Rodada normal: serviços, o resumo de cada autorizador, o incidente de quem está ruim, as fontes e o aviso")
    void rodadaNormal() {
        painelCom(servico(Documento.NFE, "RS", "Autorização", Situacao.NORMAL),
                servico(Documento.NFE, "RS", "Eventos", Situacao.FORA),
                servico(Documento.NFE, "SVRS", "Autorização", Situacao.NORMAL));

        RegistroDoMonitor.Rodada rodada = coleta.executar();

        assertThat(rodada.semInternet()).isFalse();
        assertThat(rodada.fontesComProblema()).isZero();
        assertThat(rodada.leituras()).as("4 serviços + 3 resumos").isEqualTo(7);
        assertThat(estado("NFE:RS:geral").situacao()).isEqualTo(Situacao.INSTAVEL);
        assertThat(estado("NFE:SVRS:geral").situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(estado("NFE:SP:geral").leitura().tempoMs()).isEqualTo(180);
        assertThat(registro.incidentesAbertos()).containsOnlyKeys("NFE:RS:geral");
        assertThat(registro.fontes()).extracting(RegistroDoMonitor.SituacaoDaFonte::nome)
                .containsExactly("painel-svrs-nfe", "testes-de-conexao");
        assertThat(registro.avisos()).singleElement()
                .satisfies(a -> assertThat(a.texto()).startsWith("Não há manutenção prevista"));
        assertThat(registro.disponibilidadeDesde(relogio.agora.minus(Duration.ofHours(1)))).hasSize(3);
    }

    @Test
    @DisplayName("Fonte que falha: o que ela trazia fica sem informação (sem fechar o incidente) e a falha fica registrada")
    void fonteQueFalha() {
        painelCom(servico(Documento.NFE, "RS", "Autorização", Situacao.FORA));
        coleta.executar();
        assertThat(registro.incidentesAbertos()).containsOnlyKeys("NFE:RS:geral");

        relogio.avancar(5);
        painel.resposta = agora -> FonteDeLeituras.Resultado.falhou("painel-svrs-nfe", "O portal não respondeu em 15 s.");
        RegistroDoMonitor.Rodada rodada = coleta.executar();

        assertThat(rodada.semInternet()).isFalse();
        assertThat(rodada.fontesComProblema()).isEqualTo(1);
        assertThat(estado("NFE:RS:Autorização").situacao()).isEqualTo(Situacao.SEM_INFORMACAO);
        assertThat(estado("NFE:RS:Autorização").leitura().detalhe()).isEqualTo("A fonte não trouxe este serviço nesta rodada.");
        assertThat(estado("NFE:RS:geral").situacao()).isEqualTo(Situacao.SEM_INFORMACAO);
        assertThat(estado("NFE:RS:geral").ruinsSeguidas()).isEqualTo(1);
        assertThat(registro.incidentesAbertos()).containsOnlyKeys("NFE:RS:geral");
        assertThat(registro.fontes()).filteredOn(f -> f.nome().equals("painel-svrs-nfe")).singleElement().satisfies(f -> {
            assertThat(f.erro()).isEqualTo("O portal não respondeu em 15 s.");
            assertThat(f.ultimoSucesso()).isEqualTo(relogio.agora.minus(Duration.ofMinutes(5)));
        });
        assertThat(registro.avisos()).as("sem leitura, o aviso anterior fica").hasSize(1);

        relogio.avancar(5);
        painelCom(servico(Documento.NFE, "RS", "Autorização", Situacao.NORMAL));
        coleta.executar();
        assertThat(registro.incidentesAbertos()).isEmpty();
        assertThat(registro.incidentesDesde(relogio.agora.minus(Duration.ofHours(1)))).singleElement()
                .satisfies(i -> assertThat(i.duracao(relogio.agora)).isEqualTo(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("Sem internet: nenhuma fonte alcançou ninguém; tudo fica sem informação e nada abre nem fecha")
    void semInternet() {
        painelCom(servico(Documento.NFE, "RS", "Autorização", Situacao.FORA));
        coleta.executar();

        relogio.avancar(5);
        painel.resposta = agora -> FonteDeLeituras.Resultado.falhou("painel-svrs-nfe", "Endereço do portal não encontrado (DNS).");
        conexoes.resposta = agora -> new FonteDeLeituras.Resultado("testes-de-conexao", false,
                "Nenhum servidor respondeu ao teste de conexão.",
                List.of(conexao(Documento.NFE, "SP", Situacao.FORA, null)), null, null);
        RegistroDoMonitor.Rodada rodada = coleta.executar();

        assertThat(rodada.semInternet()).isTrue();
        assertThat(estado("NFE:SP:geral").situacao()).as("o FORA do teste vira sem informação")
                .isEqualTo(Situacao.SEM_INFORMACAO);
        assertThat(estado("NFE:SP:Conexão").leitura().detalhe()).isEqualTo(ColetarSituacao.SEM_INTERNET);
        assertThat(estado("NFE:RS:geral").leitura().detalhe()).isEqualTo(ColetarSituacao.SEM_INTERNET);
        assertThat(registro.incidentesAbertos()).as("nem abre o de SP, nem fecha o do RS").containsOnlyKeys("NFE:RS:geral");
        assertThat(registro.ultimaRodada()).get().satisfies(r -> assertThat(r.semInternet()).isTrue());
    }

    @Test
    @DisplayName("Teste de conexão sem resposta: instável na primeira leitura, fora só na segunda seguida; o painel vale na hora")
    void foraSoNaSegundaLeituraDoTesteDeConexao() {
        painelCom(servico(Documento.NFE, "RS", "Autorização", Situacao.FORA));
        conexoes.resposta = agora -> new FonteDeLeituras.Resultado("testes-de-conexao", true, null,
                List.of(conexao(Documento.NFE, "SP", Situacao.FORA, null)), null, null);

        coleta.executar();
        assertThat(estado("NFE:SP:geral").situacao()).isEqualTo(Situacao.INSTAVEL);
        assertThat(estado("NFE:SP:Conexão").leitura().detalhe()).startsWith(ColetarSituacao.CONFIRMANDO);
        assertThat(estado("NFE:RS:geral").situacao()).as("o painel é a palavra do portal").isEqualTo(Situacao.FORA);

        relogio.avancar(5);
        coleta.executar();
        assertThat(estado("NFE:SP:geral").situacao()).isEqualTo(Situacao.FORA);
        assertThat(estado("NFE:SP:geral").desde()).isEqualTo(relogio.agora);
        assertThat(registro.incidentesAbertos().get("NFE:SP:geral").inicio()).as("o incidente começa na primeira leitura")
                .isEqualTo(relogio.agora.minus(Duration.ofMinutes(5)));

        relogio.avancar(5);
        conexoes.resposta = agora -> new FonteDeLeituras.Resultado("testes-de-conexao", true, null,
                List.of(conexao(Documento.NFE, "SP", Situacao.NORMAL, 120)), null, null);
        coleta.executar();
        relogio.avancar(5);
        conexoes.resposta = agora -> new FonteDeLeituras.Resultado("testes-de-conexao", true, null,
                List.of(conexao(Documento.NFE, "SP", Situacao.FORA, null)), null, null);
        coleta.executar();
        assertThat(estado("NFE:SP:geral").situacao()).as("depois de voltar, confirma de novo").isEqualTo(Situacao.INSTAVEL);
    }

    @Test
    @DisplayName("Uma fonte que estoura erro ou não volta a tempo não para as outras")
    void fonteComErroOuLenta() {
        painel.resposta = agora -> {
            throw new IllegalStateException("bug na fonte");
        };
        RegistroDoMonitor.Rodada comErro = coleta.executar();
        assertThat(comErro.fontesComProblema()).isEqualTo(1);
        assertThat(estado("NFE:SP:geral").situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(registro.fontes()).filteredOn(f -> f.nome().equals("painel-svrs-nfe")).singleElement()
                .satisfies(f -> assertThat(f.erro()).isEqualTo("Erro inesperado ao ler a fonte."));

        relogio.avancar(5);
        painel.resposta = agora -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FonteDeLeituras.Resultado.falhou("painel-svrs-nfe", "não deveria chegar aqui");
        };
        long inicio = System.nanoTime();
        RegistroDoMonitor.Rodada lenta = coleta.executar();
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
        assertThat(lenta.fontesComProblema()).isEqualTo(1);
        assertThat(registro.fontes()).filteredOn(f -> f.nome().equals("painel-svrs-nfe")).singleElement()
                .satisfies(f -> assertThat(f.erro()).isEqualTo("Não terminou a tempo."));
    }
}
