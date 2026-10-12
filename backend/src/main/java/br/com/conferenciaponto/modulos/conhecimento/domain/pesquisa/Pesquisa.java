package br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa;

import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;

import java.time.Instant;
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
 */
public record Pesquisa(ConsultaDeTexto texto, TipoDoRegistro tipo, SituacaoDoRegistro situacao, String cliente,
                       String modulo, UUID autorId, Instant desde, Instant ate, boolean soValidadas, int pagina,
                       int tamanho) {

    public Pesquisa {
        texto = texto == null ? ConsultaDeTexto.de(null) : texto;
        pagina = Math.max(1, pagina);
        tamanho = Math.max(1, tamanho);
    }

    public int deslocamento() {
        return (pagina - 1) * tamanho;
    }
}
