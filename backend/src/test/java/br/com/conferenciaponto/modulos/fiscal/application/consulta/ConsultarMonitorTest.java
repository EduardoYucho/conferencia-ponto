package br.com.conferenciaponto.modulos.fiscal.application.consulta;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.ColetarSituacao;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.ConfiguracaoDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.LerAgoraDeTeste;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.MapaDosEstados;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.conexao;
import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.servico;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O que a tela recebe, depois de rodadas de verdade no PostgreSQL de teste. */
class ConsultarMonitorTest {

    private static final ConfiguracaoDoMonitor CONFIGURACAO = new ConfiguracaoDoMonitor(
            new ConfiguracaoDoMonitor.Coleta(true, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(1),
                    Duration.ofSeconds(3), Duration.ofMinutes(30)),
            new ConfiguracaoDoMonitor.Retencao(90, 90, 7), Map.of(), Map.of(),
            Map.of("NFE", Map.of("SP", "SP", "SVRS", "SC, ES"), "CTE", Map.of("SVRS", "SC, ES, SP")),
            Map.of("SVC-AN", "SC, ES, SP"));

    private Instant agora = Instant.parse("2026-11-09T13:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    };
    private final List<Leitura> doPainel = new ArrayList<>();
    private RegistroDoMonitor registro;
    private ColetarSituacao coleta;
    private ConsultarMonitor consulta;

    @BeforeEach
    void preparar() {
        registro = RegistroDeTeste.limpo();
        FonteDeLeituras painel = new FonteDeLeituras() {
            @Override
            public String nome() {
                return "painel-svrs-nfe";
            }

            @Override
            public Resultado ler(Instant em) {
                return new Resultado(nome(), true, null, List.copyOf(doPainel), Documento.NFE, null);
            }
        };
        FonteDeLeituras testes = new FonteDeLeituras() {
            @Override
            public String nome() {
                return "testes-de-conexao";
            }

            @Override
            public Resultado ler(Instant em) {
                return new Resultado(nome(), true, null, List.of(conexao(Documento.NFE, "SP", Situacao.FORA, null),
                        conexao(Documento.NFE, "SVC-AN", Situacao.NORMAL, 300)), null, null);
            }
        };
        coleta = new ColetarSituacao(List.of(painel, testes), registro, CONFIGURACAO, clock);
        consulta = new ConsultarMonitor(registro, CONFIGURACAO.mapa(), CONFIGURACAO, clock);
    }

    @AfterEach
    void parar() {
        LerAgoraDeTeste.parar(coleta);
    }

    @Test
    @DisplayName("Quadro: cada estado com quem autoriza cada documento, a situação dele e a contingência")
    void quadro() {
        doPainel.addAll(List.of(servico(Documento.NFE, "SVRS", "Autorização", Situacao.NORMAL),
                servico(Documento.NFE, "SVRS", "Eventos", Situacao.INSTAVEL)));
        coleta.executar();

        ConsultarMonitor.PainelView p = consulta.painel();

        assertThat(p.estados()).hasSize(27);
        ConsultarMonitor.EstadoView sc = p.estados().stream().filter(e -> e.uf().equals("SC")).findFirst().orElseThrow();
        assertThat(sc.celulas()).extracting(ConsultarMonitor.CelulaView::documento).containsExactly("NFE", "CTE");
        assertThat(sc.celulas().get(0)).satisfies(c -> {
            assertThat(c.autorizador()).isEqualTo("SVRS");
            assertThat(c.nome()).isEqualTo("SEFAZ Virtual do RS");
            assertThat(c.situacao()).isEqualTo(Situacao.INSTAVEL);
        });
        assertThat(sc.celulas().get(1).situacao()).as("CT-e ainda sem leitura").isEqualTo(Situacao.SEM_INFORMACAO);
        assertThat(sc.contingencia().autorizador()).isEqualTo("SVC-AN");
        assertThat(sc.contingencia().situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(p.estados().stream().filter(e -> e.uf().equals("AC")).findFirst().orElseThrow().celulas())
                .as("fora do mapa de teste").isEmpty();

        ConsultarMonitor.AutorizadorView svrs = p.autorizadores().stream()
                .filter(a -> a.documento().equals("NFE") && a.autorizador().equals("SVRS")).findFirst().orElseThrow();
        assertThat(svrs.estados()).containsExactly("ES", "SC");
        assertThat(svrs.servicos()).extracting(ConsultarMonitor.ServicoView::servico).containsExactly("Autorização", "Eventos");
        assertThat(svrs.incidente()).isNotNull();
        ConsultarMonitor.AutorizadorView svc = p.autorizadores().stream()
                .filter(a -> a.autorizador().equals("SVC-AN")).findFirst().orElseThrow();
        assertThat(svc.contingencia()).isTrue();
        assertThat(svc.estados()).containsExactly("ES", "SC", "SP");
        assertThat(svc.tempoMs()).isEqualTo(300);
        assertThat(p.contagem()).as("SP sem resposta pela primeira vez: instável até a segunda leitura")
                .isEqualTo(new ConsultarMonitor.ContagemView(1, 2, 0, 1));
        assertThat(p.redeOscilando()).isFalse();
        assertThat(p.fontes()).extracting(ConsultarMonitor.FonteView::rotulo)
                .containsExactly("Painel da SVRS · NF-e", "Testes de conexão");
        assertThat(p.ultimaRodada().semInternet()).isFalse();
    }

    @Test
    @DisplayName("Incidentes e disponibilidade do período, com os estados afetados; período fora de 1 a 90 dias é recusado")
    void incidentesEDisponibilidade() {
        coleta.executar();
        agora = agora.plus(Duration.ofMinutes(5));
        coleta.executar();

        assertThat(consulta.incidentes(1)).singleElement().satisfies(i -> {
            assertThat(i.autorizador()).isEqualTo("SP");
            assertThat(i.nome()).isEqualTo("SEFAZ São Paulo");
            assertThat(i.estados()).containsExactly("SP");
            assertThat(i.aberto()).isTrue();
            assertThat(i.duracaoMinutos()).isEqualTo(5);
            assertThat(i.pior()).isEqualTo(Situacao.FORA);
        });
        List<ConsultarMonitor.DisponibilidadeView> d = consulta.disponibilidade(7);
        assertThat(d).extracting(ConsultarMonitor.DisponibilidadeView::autorizador).containsExactly("SP", "SVC-AN");
        assertThat(d.get(0).percentual()).isEqualTo(0.0);
        assertThat(d.get(1).percentual()).isEqualTo(100.0);

        assertThatThrownBy(() -> consulta.incidentes(0)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> consulta.disponibilidade(MapaDosEstados.UFS.size() * 10)).isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Internet deste computador oscilando: a tela avisa no alto e nada fica fora")
    void redeOscilando() {
        doPainel.add(new Leitura(new br.com.conferenciaponto.modulos.fiscal.domain.Alvo(Documento.NFE, "SVRS", "Autorização"),
                br.com.conferenciaponto.modulos.fiscal.domain.Origem.TESTE_DE_CONEXAO, Situacao.SEM_INFORMACAO,
                br.com.conferenciaponto.modulos.fiscal.domain.ProblemaLocal.REDE_OSCILANDO, null, null, agora));
        coleta.executar();

        ConsultarMonitor.PainelView p = consulta.painel();
        assertThat(p.redeOscilando()).isTrue();
    }

    @Test
    @DisplayName("Ler agora: recusa se a última rodada foi há menos de 1 minuto")
    void lerAgora() {
        var ler = LerAgoraDeTeste.criar(coleta, registro, clock);
        ler.executar();
        assertThatThrownBy(ler::executar).isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("menos de 1 minuto");
        agora = agora.plusSeconds(61);
        assertThat(ler.executar().inicio()).isEqualTo(agora);
    }
}
