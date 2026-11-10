package br.com.conferenciaponto.modulos.fiscal.infrastructure.config;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.ConfiguracaoDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.MapaDosEstados;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O fiscal.yml de verdade: liga na configuração e o mapa cobre os 27 estados em cada documento. */
class ConfiguracaoDoMonitorFiscalTest {

    private static StandardEnvironment ambiente() throws IOException {
        StandardEnvironment ambiente = new StandardEnvironment();
        new YamlPropertySourceLoader().load("fiscal", new ClassPathResource("fiscal.yml"))
                .forEach(ambiente.getPropertySources()::addLast);
        return ambiente;
    }

    @Test
    @DisplayName("fiscal.yml: coleta, retenção, painéis, testes de conexão e o mapa dos 27 estados")
    void fiscalYml() throws IOException {
        ConfiguracaoDoMonitor c = ConfiguracaoDoMonitorFiscal.ler(Binder.get(ambiente()));

        assertThat(c.coleta().ativa()).isTrue();
        assertThat(c.coleta().intervalo()).isEqualTo(Duration.ofMinutes(5));
        assertThat(c.coleta().tempoLimite()).isEqualTo(Duration.ofSeconds(15));
        assertThat(c.retencao().diasResumo()).isEqualTo(90);
        assertThat(c.paineisPorDocumento()).containsOnlyKeys(Documento.NFE, Documento.NFCE, Documento.CTE, Documento.MDFE);
        assertThat(c.conexoesPorDocumento().get(Documento.NFE)).containsKeys("SP", "SVAN", "SVC-AN", "SVC-RS");

        MapaDosEstados mapa = c.mapa();
        for (Documento d : Documento.values()) {
            for (String uf : MapaDosEstados.UFS) {
                assertThat(mapa.autorizador(d, uf)).as("%s de %s", d.rotulo(), uf).isPresent();
            }
        }
        for (String uf : MapaDosEstados.UFS) {
            assertThat(mapa.contingencia(uf)).as("contingência de %s", uf).isPresent();
        }
        assertThat(mapa.autorizador(Documento.NFE, "SC")).contains("SVRS");
        assertThat(mapa.autorizador(Documento.NFE, "MA")).contains("SVAN");
        assertThat(mapa.autorizador(Documento.CTE, "PE")).contains("SVSP");

        // cada autorizador que não está nos painéis tem teste de conexão
        mapa.autorizadores().forEach((documento, autorizadores) -> autorizadores.stream()
                .filter(a -> !a.equals("SVRS") && !a.equals("RS"))
                .forEach(a -> assertThat(c.conexoesPorDocumento().getOrDefault(documento, Map.of()))
                        .as("teste de conexão de %s na %s", a, documento.rotulo()).containsKey(a)));

        assertThat(ConfiguracaoDoMonitorFiscal.fontes(c)).extracting(FonteDeLeituras::nome).containsExactly(
                "painel-svrs-nfe", "painel-svrs-nfce", "painel-svrs-cte", "painel-svrs-mdfe", "testes-de-conexao");
    }

    @Test
    @DisplayName("O config/application.yml da máquina muda um valor sem repetir o resto; sem fiscal.yml, erro claro")
    void valoresDaMaquinaEFalta() throws IOException {
        StandardEnvironment ambiente = ambiente();
        ambiente.getPropertySources().addFirst(new MapPropertySource("maquina", Map.of("fiscal.coleta.ativa", "false",
                "fiscal.conexoes.NFE.SP", "outro.exemplo:8443")));
        ConfiguracaoDoMonitor c = ConfiguracaoDoMonitorFiscal.ler(Binder.get(ambiente));
        assertThat(c.coleta().ativa()).isFalse();
        assertThat(c.coleta().intervalo()).isEqualTo(Duration.ofMinutes(5));
        assertThat(c.conexoesPorDocumento().get(Documento.NFE)).containsEntry("SP", "outro.exemplo:8443")
                .containsKey("MG");

        assertThatThrownBy(() -> ConfiguracaoDoMonitorFiscal.ler(Binder.get(new StandardEnvironment())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fiscal.yml");
    }
}
