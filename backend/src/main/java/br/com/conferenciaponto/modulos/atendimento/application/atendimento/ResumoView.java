package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ResumoDoAtendimento;

import java.time.Instant;
import java.util.UUID;

/** Uma linha da lista de atendimentos. */
public record ResumoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                         Instant criadoEm, int mensagens, int anexos, Instant linksValidosAte, boolean linksVencidos) {

    static ResumoView de(ResumoDoAtendimento r, Instant agora) {
        return new ResumoView(r.id(), r.chamado(), r.contato(), r.inicio(), r.fim(), r.situacao().codigo(), r.criadoEm(),
                r.mensagens(), r.anexos(), r.linksValidosAte(), vencido(r.linksValidosAte(), agora));
    }

    static boolean vencido(Instant validoAte, Instant agora) {
        return validoAte != null && !validoAte.isAfter(agora);
    }
}
