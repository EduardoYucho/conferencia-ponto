package br.com.conferenciaponto.modulos.fiscal.infrastructure.config;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.ColetarSituacao;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.ConfiguracaoDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.MapaDosEstados;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta.PainelDaSvrs;
import br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta.SondaDeConexao;
import br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta.TestesDeConexao;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Monta o monitor a partir do fiscal.yml (os valores desta máquina vão em config/application.yml): a configuração,
 * o mapa dos estados (conferido ao subir: um estado em dois autorizadores impede a aplicação de subir, com a
 * mensagem do erro), as fontes e a rodada. O agendamento é ligado aqui para o módulo não depender do ponto.
 */
@Configuration
@EnableScheduling
public class ConfiguracaoDoMonitorFiscal {

    /** Os testes de conexão ao mesmo tempo. */
    static final int TESTES_AO_MESMO_TEMPO = 8;

    @Bean
    public ConfiguracaoDoMonitor configuracaoDoMonitor(Environment ambiente) {
        return ler(Binder.get(ambiente));
    }

    @Bean
    public MapaDosEstados mapaDosEstados(ConfiguracaoDoMonitor configuracao) {
        return configuracao.mapa();
    }

    @Bean
    public ColetarSituacao coletarSituacao(ConfiguracaoDoMonitor configuracao, RegistroDoMonitor registro, Clock clock) {
        return new ColetarSituacao(fontes(configuracao), registro, configuracao, clock);
    }

    public static ConfiguracaoDoMonitor ler(Binder binder) {
        ConfiguracaoDoMonitor configuracao = binder.bind("fiscal", ConfiguracaoDoMonitor.class)
                .orElseThrow(() -> new IllegalStateException("Falta a configuração do monitor fiscal (fiscal.yml)."));
        if (configuracao.coleta() == null || configuracao.retencao() == null) {
            throw new IllegalStateException("Falta fiscal.coleta ou fiscal.retencao na configuração do monitor fiscal.");
        }
        return configuracao;
    }

    /** Um painel por documento e os testes de conexão (se houver endereço configurado). */
    public static List<FonteDeLeituras> fontes(ConfiguracaoDoMonitor configuracao) {
        ConfiguracaoDoMonitor.Coleta c = configuracao.coleta();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // os portais do governo, nem sempre afinados com HTTP/2
                .connectTimeout(c.tempoLimite())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        List<FonteDeLeituras> fontes = new ArrayList<>();
        configuracao.paineisPorDocumento().forEach((documento, endereco) ->
                fontes.add(new PainelDaSvrs(documento, endereco, http, c.tempoLimite(), c.verificacaoAntiga())));
        TestesDeConexao testes = new TestesDeConexao(configuracao.conexoesPorDocumento(),
                new SondaDeConexao(c.tempoLimite(), c.lento()), TESTES_AO_MESMO_TEMPO, c.tempoLimite());
        if (!testes.vazio()) {
            fontes.add(testes);
        }
        return fontes;
    }
}
