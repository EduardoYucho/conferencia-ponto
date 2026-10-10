package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.util.List;
import java.util.UUID;

/** A situação do atendimento e de cada arquivo, como o acompanhamento ao vivo mostra. */
public record ProgressoDoAtendimento(UUID atendimentoId, UUID usuarioId, SituacaoDoAtendimento situacao,
                                     String motivoPausa, List<ArquivoDoAtendimento> arquivos) {

    public ProgressoDoAtendimento {
        arquivos = List.copyOf(arquivos);
    }

    /** Anexos da conversa já resolvidos (baixados, enviados, vencidos...) sobre o total; 100 sem anexos. */
    public int percentual() {
        List<ArquivoDoAtendimento> anexos = arquivos.stream()
                .filter(ArquivoDoAtendimento::daConversa)
                .filter(a -> !SituacaoDoArquivo.REMOVIDO.codigo().equals(a.situacao()))
                .toList();
        if (anexos.isEmpty()) {
            return 100;
        }
        long prontos = anexos.stream().filter(a -> SituacaoDoArquivo.doCodigo(a.situacao()).terminada()).count();
        return (int) (prontos * 100 / anexos.size());
    }
}
