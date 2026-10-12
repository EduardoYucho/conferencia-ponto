package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
import br.com.conferenciaponto.modulos.atendimento.application.evento.VetoresDeTexto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Os vetores do gerador, de mentira: só quem tem "chave" (a lista do teste) recebe vetor; o vetor é o de palavras do
 * {@link ModeloDeMentira} (textos com as mesmas palavras ficam parecidos), e textos marcados ganham um vetor fixo.
 */
public final class VetoresDeMentira implements VetoresDeTexto {

    public record Pedido(UUID usuarioId, String modelo, int dimensao, String texto) {
    }

    private final Set<UUID> comChave = ConcurrentHashMap.newKeySet();
    private final Map<String, float[]> fixos = new ConcurrentHashMap<>();
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();
    private final Deque<FalhaNoVetor> falhas = new ArrayDeque<>();

    public VetoresDeMentira comChave(UUID... usuarios) {
        comChave.addAll(List.of(usuarios));
        return this;
    }

    public VetoresDeMentira semChave(UUID usuario) {
        comChave.remove(usuario);
        return this;
    }

    /** Um texto que contém {@code trecho} recebe este vetor. */
    public VetoresDeMentira fixo(String trecho, float[] vetor) {
        fixos.put(trecho, vetor);
        return this;
    }

    public synchronized VetoresDeMentira falhar(FalhaNoVetor falha) {
        falhas.add(falha);
        return this;
    }

    public List<Pedido> pedidos() {
        return pedidos;
    }

    public List<Pedido> pedidosDe(UUID usuario) {
        return pedidos.stream().filter(p -> p.usuarioId().equals(usuario)).toList();
    }

    @Override
    public float[] calcular(UUID usuarioId, String modelo, int dimensao, String texto) {
        if (!comChave.contains(usuarioId)) {
            throw new FalhaNoVetor(FalhaNoVetor.Motivo.SEM_CHAVE, "Cadastre sua chave do Gemini (Atendimentos → Minha chave "
                    + "do Gemini) para usar a pesquisa por significado.");
        }
        pedidos.add(new Pedido(usuarioId, modelo, dimensao, texto));
        synchronized (this) {
            if (!falhas.isEmpty()) {
                throw falhas.removeFirst();
            }
        }
        for (Map.Entry<String, float[]> fixo : fixos.entrySet()) {
            if (texto.contains(fixo.getKey())) {
                return fixo.getValue().clone();
            }
        }
        return ModeloDeMentira.vetorDePalavras(texto, dimensao);
    }

    public ObjectProvider<VetoresDeTexto> provedor() {
        return new StaticListableBeanFactory(Map.of("vetores", this)).getBeanProvider(VetoresDeTexto.class);
    }

    public static ObjectProvider<VetoresDeTexto> nenhum() {
        return new StaticListableBeanFactory().getBeanProvider(VetoresDeTexto.class);
    }
}
