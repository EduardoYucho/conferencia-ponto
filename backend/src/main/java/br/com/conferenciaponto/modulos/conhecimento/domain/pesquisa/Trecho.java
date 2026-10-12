package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import java.util.List;

/**
 * Um texto em pedaços, com o que bateu com a pesquisa destacado. A tela monta os pedaços como texto (nunca como
 * HTML), então nada do registro vira marcação.
 */
public record Trecho(List<Parte> partes) {

    public Trecho {
        partes = List.copyOf(partes);
    }

    public static Trecho simples(String texto) {
        return new Trecho(texto == null || texto.isEmpty() ? List.of() : List.of(new Parte(texto, false)));
    }

    public boolean comDestaque() {
        return partes.stream().anyMatch(Parte::destaque);
    }

    /** O texto sem as marcas. */
    public String texto() {
        StringBuilder texto = new StringBuilder();
        partes.forEach(p -> texto.append(p.texto()));
        return texto.toString();
    }

    public record Parte(String texto, boolean destaque) {
    }
}
