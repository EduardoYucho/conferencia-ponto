package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoAtualizado;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** O SSE do gerador entrega o progresso só ao dono do atendimento (nem o administrador recebe o dos outros). */
class EmissorDoGeradorTest {

    /** Uma conexão em memória que guarda o que recebeu. */
    private static final class Recebidos implements EmissorDoGerador.Conexao {
        final List<Object> eventos = new CopyOnWriteArrayList<>();
        boolean caiu;

        @Override
        public void enviar(String evento, Object dados) throws IOException {
            if (caiu) {
                throw new IOException("aba fechada");
            }
            eventos.add(evento + ":" + ((ProgressoView) dados).atendimentoId());
        }

        @Override
        public void ping() throws IOException {
            if (caiu) {
                throw new IOException("aba fechada");
            }
        }
    }

    private static ProgressoAtualizado progresso(UUID dono, UUID atendimento) {
        return new ProgressoAtualizado(dono, new ProgressoView(atendimento, "processando", null, 50, List.of()));
    }

    @Test
    void cadaEventoVaiSoParaODono() {
        EmissorDoGerador emissor = new EmissorDoGerador();
        UUID maria = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Recebidos abaDaMaria = new Recebidos();
        Recebidos outraAbaDaMaria = new Recebidos();
        Recebidos abaDoAdmin = new Recebidos();
        emissor.registrar(abaDaMaria, maria);
        emissor.registrar(outraAbaDaMaria, maria);
        emissor.registrar(abaDoAdmin, admin);
        UUID atendimento = UUID.randomUUID();

        emissor.aoAtualizarProgresso(progresso(maria, atendimento));

        assertThat(abaDaMaria.eventos).containsExactly("atendimento-progresso:" + atendimento);
        assertThat(outraAbaDaMaria.eventos).hasSize(1);
        assertThat(abaDoAdmin.eventos).isEmpty();
    }

    @Test
    void conexaoQueCaiSaiDaLista() {
        EmissorDoGerador emissor = new EmissorDoGerador();
        UUID maria = UUID.randomUUID();
        Recebidos aba = new Recebidos();
        Recebidos fechada = new Recebidos();
        fechada.caiu = true;
        emissor.registrar(aba, maria);
        emissor.registrar(fechada, maria);

        emissor.aoAtualizarProgresso(progresso(maria, UUID.randomUUID()));
        emissor.manterVivas();

        assertThat(emissor.conexoes()).isEqualTo(1);
        assertThat(aba.eventos).hasSize(1);
    }
}
