package br.com.conferenciaponto.modulos.fiscal.domain;

import java.util.Map;

/** Como cada autorizador aparece na tela. */
public final class NomesDosAutorizadores {

    private static final Map<String, String> NOMES = Map.ofEntries(
            Map.entry("AM", "SEFAZ Amazonas"), Map.entry("BA", "SEFAZ Bahia"), Map.entry("GO", "SEFAZ Goiás"),
            Map.entry("MG", "SEFAZ Minas Gerais"), Map.entry("MS", "SEFAZ Mato Grosso do Sul"),
            Map.entry("MT", "SEFAZ Mato Grosso"), Map.entry("PE", "SEFAZ Pernambuco"), Map.entry("PR", "SEFAZ Paraná"),
            Map.entry("RS", "SEFAZ Rio Grande do Sul"), Map.entry("SP", "SEFAZ São Paulo"),
            Map.entry("SVAN", "SEFAZ Virtual do Ambiente Nacional"), Map.entry("SVRS", "SEFAZ Virtual do RS"),
            Map.entry("SVSP", "SEFAZ Virtual de SP"), Map.entry("SVC-AN", "Contingência SVC-AN"),
            Map.entry("SVC-RS", "Contingência SVC-RS"));

    private NomesDosAutorizadores() {
    }

    public static String nome(String sigla) {
        return NOMES.getOrDefault(sigla, sigla);
    }
}
