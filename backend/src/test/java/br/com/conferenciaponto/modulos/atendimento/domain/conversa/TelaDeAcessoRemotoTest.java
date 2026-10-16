package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A foto da tela do programa de acesso remoto (com o ID e a senha) é reconhecida; a tela do sistema, não. */
class TelaDeAcessoRemotoTest {

    @Test
    void reconheceATelaDoPrograma() {
        assertThat(TelaDeAcessoRemoto.mostra("A foto mostra a tela do software de acesso remoto UltraViewer aberta sobre "
                + "a área de trabalho do computador")).isTrue();
        assertThat(TelaDeAcessoRemoto.mostra("tela UltraViewer")).isTrue();
        assertThat(TelaDeAcessoRemoto.mostra("Janela do AnyDesk com o endereço desta estação.")).isTrue();
        assertThat(TelaDeAcessoRemoto.mostra("Print do programa TeamViewer pedindo a senha.")).isTrue();
    }

    @Test
    void telaDoSistemaVistaPeloAcessoRemotoContinuaValendo() {
        assertThat(TelaDeAcessoRemoto.mostra("Tela de Pedidos de Vendas (EQR76) vista pelo AnyDesk")).isFalse();
        assertThat(TelaDeAcessoRemoto.mostra("A tela Fechamento de Caixa (Eq230), na sessão do UltraViewer.")).isFalse();
        assertThat(TelaDeAcessoRemoto.mostra("tela PAF [SisLojas] - [Menus]")).isFalse();
        assertThat(TelaDeAcessoRemoto.mostra("O cliente usou o AnyDesk.")).as("sem falar de tela").isFalse();
        assertThat(TelaDeAcessoRemoto.mostra(null)).isFalse();
    }
}
