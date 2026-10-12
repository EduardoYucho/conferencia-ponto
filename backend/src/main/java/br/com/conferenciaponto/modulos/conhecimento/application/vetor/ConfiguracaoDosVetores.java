package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A pesquisa por significado ({@code conhecimento.vetor.*} e {@code conhecimento.parecidos.*} no atendimento.yml).
 *
 * @param similaridadeNaPesquisa o cosseno mínimo para um registro entrar na pesquisa só pelo significado
 * @param maximoNaPesquisa       quantos registros parecidos entram na fusão com a pesquisa exata
 * @param lote                   quantos vetores pendentes são gerados por rodada (a cada {@code intervalo})
 * @param limiteDeParecidos      quantos casos parecidos aparecem nos chamados
 * @param similaridadeDeParecido o cosseno mínimo para o aviso de caso parecido
 */
@Component
public record ConfiguracaoDosVetores(String modelo, int dimensao, double similaridadeNaPesquisa, int maximoNaPesquisa,
                                     int lote, int limiteDeParecidos, double similaridadeDeParecido) {

    public ConfiguracaoDosVetores(@Value("${conhecimento.vetor.modelo:gemini-embedding-2}") String modelo,
                                  @Value("${conhecimento.vetor.dimensao:768}") int dimensao,
                                  @Value("${conhecimento.vetor.similaridade-minima:0.6}") double similaridadeNaPesquisa,
                                  @Value("${conhecimento.vetor.maximo-na-pesquisa:30}") int maximoNaPesquisa,
                                  @Value("${conhecimento.vetor.lote:20}") int lote,
                                  @Value("${conhecimento.parecidos.limite:5}") int limiteDeParecidos,
                                  @Value("${conhecimento.parecidos.similaridade-minima:0.75}") double similaridadeDeParecido) {
        this.modelo = modelo;
        this.dimensao = dimensao;
        this.similaridadeNaPesquisa = similaridadeNaPesquisa;
        this.maximoNaPesquisa = maximoNaPesquisa;
        this.lote = lote;
        this.limiteDeParecidos = limiteDeParecidos;
        this.similaridadeDeParecido = similaridadeDeParecido;
    }
}
