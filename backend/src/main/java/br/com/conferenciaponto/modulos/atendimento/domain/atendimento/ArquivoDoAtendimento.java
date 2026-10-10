package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo do atendimento como está no banco (sem a URL de download, que só o download usa).
 *
 * @param origem       anexo_conversa, ligacao, video ou print_extra
 * @param tamanho      em bytes, depois de baixado ou enviado (null antes)
 * @param erroMensagem o motivo da última falha, para a pessoa ler (null sem falha)
 * @param caminho      relativo à pasta dos atendimentos (null enquanto não há arquivo); nunca vai para a tela
 */
public record ArquivoDoAtendimento(UUID id, String origem, int ordem, String nome, CategoriaDoArquivo categoria,
                                   String situacao, Instant urlValidaAte, Integer mensagemOrdem, Instant momento,
                                   Long tamanho, String erroCodigo, String erroMensagem, String caminho) {

    public boolean daConversa() {
        return OrigemDoArquivo.ANEXO_CONVERSA.codigo().equals(origem);
    }
}
