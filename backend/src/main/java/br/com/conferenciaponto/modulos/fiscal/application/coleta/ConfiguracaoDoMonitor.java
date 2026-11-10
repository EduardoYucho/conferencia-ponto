package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.MapaDosEstados;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A configuração do monitor ({@code fiscal.*}, em fiscal.yml; os valores desta máquina vão em config/application.yml).
 *
 * @param paineis       documento → endereço do painel de disponibilidade da SVRS
 * @param conexoes      documento → autorizador → endereço testado (host ou host:porta)
 * @param autorizadores documento → autorizador → as UFs que ele autoriza
 * @param contingencia  contingência da NF-e → as UFs dela
 */
public record ConfiguracaoDoMonitor(Coleta coleta, Retencao retencao, Map<String, String> paineis,
                                    Map<String, Map<String, String>> conexoes,
                                    Map<String, Map<String, String>> autorizadores, Map<String, String> contingencia) {

    /**
     * @param ativa              liga a coleta automática
     * @param intervalo          entre uma rodada e outra (5 min: o ritmo em que os painéis se atualizam)
     * @param primeiraVez        quanto esperar depois de subir para a primeira rodada
     * @param tempoLimite        para cada página e cada teste de conexão
     * @param lento              acima disto, o teste de conexão conta como instável
     * @param verificacaoAntiga  um serviço que o painel não verifica há mais que isto fica sem informação
     */
    public record Coleta(boolean ativa, Duration intervalo, Duration primeiraVez, Duration tempoLimite, Duration lento,
                         Duration verificacaoAntiga) {
    }

    /** Por quantos dias guardar os resumos por hora, as mudanças e as rodadas (os incidentes ficam para sempre). */
    public record Retencao(int diasResumo, int diasMudancas, int diasRodadas) {
    }

    public ConfiguracaoDoMonitor {
        paineis = paineis == null ? Map.of() : paineis;
        conexoes = conexoes == null ? Map.of() : conexoes;
        autorizadores = autorizadores == null ? Map.of() : autorizadores;
        contingencia = contingencia == null ? Map.of() : contingencia;
    }

    public MapaDosEstados mapa() {
        Map<Documento, Map<String, String>> porDocumento = new EnumMap<>(Documento.class);
        autorizadores.forEach((codigo, porAutorizador) -> porDocumento.put(documento(codigo), maiusculas(porAutorizador)));
        return new MapaDosEstados(porDocumento, maiusculas(contingencia));
    }

    /** Documento → endereço do painel. */
    public Map<Documento, String> paineisPorDocumento() {
        Map<Documento, String> porDocumento = new EnumMap<>(Documento.class);
        paineis.forEach((codigo, url) -> porDocumento.put(documento(codigo), url.strip()));
        return porDocumento;
    }

    /** Documento → autorizador → endereço testado. */
    public Map<Documento, Map<String, String>> conexoesPorDocumento() {
        Map<Documento, Map<String, String>> porDocumento = new EnumMap<>(Documento.class);
        conexoes.forEach((codigo, porAutorizador) -> porDocumento.put(documento(codigo), maiusculas(porAutorizador)));
        return porDocumento;
    }

    private static Documento documento(String codigo) {
        return Documento.doCodigo(codigo).orElseThrow(() ->
                new IllegalArgumentException("Documento desconhecido na configuração do monitor fiscal: " + codigo));
    }

    /** As chaves da configuração podem chegar em minúsculas (é assim que o Spring lê as chaves dos mapas). */
    private static Map<String, String> maiusculas(Map<String, String> mapa) {
        Map<String, String> copia = new LinkedHashMap<>();
        mapa.forEach((chave, valor) -> copia.put(chave.strip().toUpperCase(Locale.ROOT), valor));
        return copia;
    }
}
