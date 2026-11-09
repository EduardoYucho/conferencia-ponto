package br.com.conferenciaponto.modulos.fiscal.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Quem autoriza cada documento de cada estado (muitos estados usam a SVRS ou a SVAN, não a própria SEFAZ) e a
 * contingência de cada estado na NF-e (SVC-AN ou SVC-RS). Vem da configuração ({@code fiscal.autorizadores} e
 * {@code fiscal.contingencia}), porque muda com o tempo: um estado que migra para a SVRS muda uma linha lá.
 */
public final class MapaDosEstados {

    /** As 27 unidades da federação, na ordem alfabética das siglas. */
    public static final List<String> UFS = List.of("AC", "AL", "AM", "AP", "BA", "CE", "DF", "ES", "GO", "MA", "MG",
            "MS", "MT", "PA", "PB", "PE", "PI", "PR", "RJ", "RN", "RO", "RR", "RS", "SC", "SE", "SP", "TO");

    private final Map<Documento, Map<String, String>> autorizadorDaUf = new EnumMap<>(Documento.class);
    private final Map<String, String> contingenciaDaUf = new TreeMap<>();

    /**
     * @param autorizadores documento → autorizador → as UFs que ele autoriza ("AC,AL,AP")
     * @param contingencia  contingência da NF-e → as UFs dela
     */
    public MapaDosEstados(Map<Documento, Map<String, String>> autorizadores, Map<String, String> contingencia) {
        autorizadores.forEach((documento, porAutorizador) -> porAutorizador.forEach((autorizador, ufs) -> {
            for (String uf : ufs(ufs)) {
                String anterior = autorizadorDaUf.computeIfAbsent(documento, d -> new TreeMap<>())
                        .put(uf, sigla(autorizador));
                if (anterior != null && !anterior.equals(sigla(autorizador))) {
                    throw new IllegalArgumentException("%s de %s aparece em %s e em %s".formatted(documento.rotulo(), uf,
                            anterior, sigla(autorizador)));
                }
            }
        }));
        contingencia.forEach((svc, ufs) -> ufs(ufs).forEach(uf -> contingenciaDaUf.put(uf, sigla(svc))));
    }

    /** Quem autoriza o documento deste estado (vazio = o documento não está no mapa para o estado). */
    public Optional<String> autorizador(Documento documento, String uf) {
        return Optional.ofNullable(autorizadorDaUf.getOrDefault(documento, Map.of()).get(uf));
    }

    /** A contingência da NF-e deste estado (SVC-AN ou SVC-RS). */
    public Optional<String> contingencia(String uf) {
        return Optional.ofNullable(contingenciaDaUf.get(uf));
    }

    /** Os estados que este autorizador atende neste documento. */
    public List<String> estados(Documento documento, String autorizador) {
        List<String> estados = new ArrayList<>();
        autorizadorDaUf.getOrDefault(documento, Map.of()).forEach((uf, a) -> {
            if (a.equals(autorizador)) {
                estados.add(uf);
            }
        });
        return estados;
    }

    /** Os estados que usam esta contingência. */
    public List<String> estadosDaContingencia(String svc) {
        List<String> estados = new ArrayList<>();
        contingenciaDaUf.forEach((uf, s) -> {
            if (s.equals(svc)) {
                estados.add(uf);
            }
        });
        return estados;
    }

    /** Todos os autorizadores do mapa, por documento (para conferir a configuração). */
    public Map<Documento, Set<String>> autorizadores() {
        Map<Documento, Set<String>> todos = new LinkedHashMap<>();
        autorizadorDaUf.forEach((d, porUf) -> todos.put(d, Set.copyOf(porUf.values())));
        return todos;
    }

    static String sigla(String autorizador) {
        return autorizador.strip().toUpperCase(Locale.ROOT);
    }

    private static List<String> ufs(String lista) {
        List<String> ufs = new ArrayList<>();
        for (String parte : lista.split("[,;\\s]+")) {
            String uf = parte.strip().toUpperCase(Locale.ROOT);
            if (uf.isEmpty()) {
                continue;
            }
            if (!UFS.contains(uf)) {
                throw new IllegalArgumentException("Estado desconhecido no mapa do monitor fiscal: " + parte);
            }
            ufs.add(uf);
        }
        return ufs;
    }
}
