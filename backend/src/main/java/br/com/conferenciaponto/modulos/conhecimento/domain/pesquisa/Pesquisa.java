package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Uma pesquisa na base: o texto (opcional) e os filtros. Sem texto, lista do mais novo para o mais antigo; com texto,
 * do que mais bateu para o que menos bateu.
 *
 * @param cliente  um pedaço do nome do cliente (sem diferença de acento e maiúsculas)
 * @param modulo   um pedaço do módulo
 * @param desde    criados a partir deste instante (null = sem limite)
 * @param ate      criados antes deste instante (null = sem limite)
 * @param pagina   a partir de 1
 * @param porSignificado os registros parecidos pelo significado, do mais para o menos parecido (vazio = só a
 *                       pesquisa exata); entram na lista mesmo sem bater no texto, e a ordem final funde as duas
 *                       posições
 */
public record Pesquisa(ConsultaDeTexto texto, TipoDoRegistro tipo, SituacaoDoRegistro situacao, String cliente,
                       String modulo, UUID autorId, Instant desde, Instant ate, boolean soValidadas, int pagina,
                       int tamanho, List<Semelhante> porSignificado) {

    public Pesquisa {
        texto = texto == null ? ConsultaDeTexto.de(null) : texto;
        pagina = Math.max(1, pagina);
        tamanho = Math.max(1, tamanho);
        porSignificado = porSignificado == null ? List.of() : List.copyOf(porSignificado);
    }

    public Pesquisa(ConsultaDeTexto texto, TipoDoRegistro tipo, SituacaoDoRegistro situacao, String cliente,
                    String modulo, UUID autorId, Instant desde, Instant ate, boolean soValidadas, int pagina, int tamanho) {
        this(texto, tipo, situacao, cliente, modulo, autorId, desde, ate, soValidadas, pagina, tamanho, List.of());
    }

    /** Página absurda (?pagina=200000000) dá uma lista vazia, não um OFFSET que estoura. */
    public int deslocamento() {
        return (int) Math.min(Integer.MAX_VALUE, (long) (pagina - 1) * tamanho);
    }
}
