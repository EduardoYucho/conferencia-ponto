package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Os vetores dos registros na memória, de um modelo e um tamanho só. Os vetores são normalizados, então o cosseno
 * é o produto escalar: 10 mil registros de 768 valores ocupam uns 30 MB e a comparação leva milissegundos.
 */
public final class IndiceEmMemoria {

    private final String modelo;
    private final int dimensao;
    private final Map<UUID, float[]> vetores = new ConcurrentHashMap<>();

    public IndiceEmMemoria(String modelo, int dimensao) {
        this.modelo = Objects.requireNonNull(modelo, "modelo");
        this.dimensao = dimensao;
    }

    public String modelo() {
        return modelo;
    }

    public int dimensao() {
        return dimensao;
    }

    public int tamanho() {
        return vetores.size();
    }

    public boolean tem(UUID registroId) {
        return vetores.containsKey(registroId);
    }

    /** Põe (ou troca) o vetor do registro; vetor de outro tamanho é recusado. */
    public void colocar(UUID registroId, float[] vetor) {
        if (vetor == null || vetor.length != dimensao) {
            throw new IllegalArgumentException("vetor com " + (vetor == null ? 0 : vetor.length) + " valores; o índice usa "
                    + dimensao);
        }
        vetores.put(registroId, normalizado(vetor));
    }

    public void tirar(UUID registroId) {
        vetores.remove(registroId);
    }

    /**
     * Os mais parecidos com a consulta, do mais para o menos parecido.
     *
     * @param minimo  similaridade mínima (cosseno)
     * @param maximo  quantos no máximo
     * @param aceitar quais registros podem entrar (null = todos)
     */
    public List<Semelhante> maisParecidos(float[] consulta, double minimo, int maximo, Predicate<UUID> aceitar) {
        if (consulta == null || consulta.length != dimensao || maximo <= 0) {
            return List.of();
        }
        float[] q = normalizado(consulta);
        List<Semelhante> achados = new ArrayList<>();
        for (Map.Entry<UUID, float[]> e : vetores.entrySet()) {
            if (aceitar != null && !aceitar.test(e.getKey())) {
                continue;
            }
            double s = produto(q, e.getValue());
            if (s >= minimo) {
                achados.add(new Semelhante(e.getKey(), s));
            }
        }
        achados.sort(Comparator.comparingDouble(Semelhante::similaridade).reversed()
                .thenComparing(s -> s.registroId().toString()));
        return achados.size() <= maximo ? achados : List.copyOf(achados.subList(0, maximo));
    }

    static double produto(float[] a, float[] b) {
        double soma = 0;
        for (int i = 0; i < a.length; i++) {
            soma += (double) a[i] * b[i];
        }
        return soma;
    }

    /** O vetor com tamanho 1 (o cosseno vira o produto escalar). */
    public static float[] normalizado(float[] vetor) {
        double soma = 0;
        for (float v : vetor) {
            soma += (double) v * v;
        }
        if (soma == 0 || Math.abs(soma - 1) < 1e-6) {
            return vetor.clone();
        }
        double norma = Math.sqrt(soma);
        float[] n = new float[vetor.length];
        for (int i = 0; i < vetor.length; i++) {
            n[i] = (float) (vetor[i] / norma);
        }
        return n;
    }
}
