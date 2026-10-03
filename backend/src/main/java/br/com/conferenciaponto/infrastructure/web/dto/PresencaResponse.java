package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.PresencaView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Painel da equipe pronto para a tela: a situação, o rótulo e o motivo de cada pessoa já vêm escritos (a tela
 * não decide nada, só mostra).
 */
public record PresencaResponse(LocalDate hoje, LocalTime agora, int total, int online, int emIntervalo, int offline,
                               List<Pessoa> pessoas) {

    public record Pessoa(UUID id, String login, String nome, boolean euMesmo, boolean online, String situacao,
                         String situacaoRotulo, String motivo, String detalhe, LocalTime desde, String horario,
                         List<LocalTime> batidas, boolean atencao, boolean alemDoHorario) {
    }

    public static PresencaResponse de(PresencaView view) {
        int online = (int) view.online();
        int intervalo = (int) view.emIntervalo();
        return new PresencaResponse(view.hoje(), view.agora(), view.pessoas().size(), online, intervalo,
                view.pessoas().size() - online - intervalo,
                view.pessoas().stream().map(p -> new Pessoa(p.id(), p.login(), p.nome(), p.euMesmo(), p.online(),
                        p.situacao().name(), p.situacao().rotulo(), p.motivo(), p.detalhe(), p.desde(), p.horario(),
                        p.batidas(), p.atencao(), p.alemDoHorario())).toList());
    }
}
