package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.List;

/** Um atendimento com tudo o que a tela de detalhe mostra. */
public record AtendimentoGuardado(ResumoDoAtendimento resumo, ConversaGuardada conversa, List<ArquivoDoAtendimento> arquivos,
                                  int versaoLeitor, Instant apagarArquivosEm, Instant arquivosApagadosEm,
                                  String motivoPausa) {

    public AtendimentoGuardado {
        arquivos = List.copyOf(arquivos);
    }
}
