package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.AvisoDeModulo;
import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O aviso que um módulo pede vira um aviso do sino, uma vez por chave e no tamanho da tabela. */
class AvisoDeModuloTest {

    private final NotificacaoRepositoryEmMemoria repositorio = new NotificacaoRepositoryEmMemoria();
    private final List<Object> eventos = new ArrayList<>();
    private final NotificacoesUseCase notificacoes = new NotificacoesUseCase(repositorio, eventos::add,
            Clock.fixed(Instant.parse("2026-11-17T13:00:00Z"), ZoneOffset.UTC));
    private final UUID maria = UUID.randomUUID();

    @Test
    void viraUmAvisoDoSinoUmaVezSo() {
        AvisoDeModulo aviso = new AvisoDeModulo(maria, "ATENDIMENTO_PARADO:1:2026-11-16", "Atendimento parado",
                "O atendimento do chamado 1 está pausado.", "/atendimentos/1");

        notificacoes.aoPedirAviso(aviso);
        notificacoes.aoPedirAviso(aviso);

        List<Notificacao> caixa = repositorio.listarRecentes(maria, 10);
        assertThat(caixa).hasSize(1);
        Notificacao n = caixa.get(0);
        assertThat(n.tipo()).isEqualTo(TipoNotificacao.MODULO);
        assertThat(n.chave()).isEqualTo("MODULO:ATENDIMENTO_PARADO:1:2026-11-16");
        assertThat(n.titulo()).isEqualTo("Atendimento parado");
        assertThat(n.link()).isEqualTo("/atendimentos/1");
        assertThat(eventos).hasSize(1).first().isInstanceOf(NotificacaoCriadaEvento.class); // a tela aberta recebe
    }

    @Test
    void textoLongoECortadoNoTamanhoDaTabela() {
        notificacoes.aoPedirAviso(new AvisoDeModulo(maria, "X".repeat(200), "T".repeat(200), "M".repeat(900), null));

        Notificacao n = repositorio.listarRecentes(maria, 10).get(0);
        assertThat(n.chave()).hasSize(120).startsWith("MODULO:").endsWith("…");
        assertThat(n.titulo()).hasSize(120);
        assertThat(n.mensagem()).hasSize(500);
        assertThat(n.link()).isNull();
    }
}
