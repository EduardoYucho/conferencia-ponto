package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DadosDoRegistroTest {

    @Test
    void limpaOQueAPessoaDigitou() {
        DadosDoRegistro dados = DadosDoRegistro.conferir("  Erro   ao gravar\n o pedido ", "ERRO", " Padaria ", "não identificado",
                "", "Pedidos", " VEN010 ", "Linha 1\r\n\r\n\r\n\r\nLinha 2   \n", "Não identificado.", null, "Acertar o estoque");

        assertThat(dados.titulo()).isEqualTo("Erro ao gravar o pedido");
        assertThat(dados.tipo()).isEqualTo(TipoDoRegistro.ERRO);
        assertThat(dados.cliente()).isEqualTo("Padaria");
        assertThat(dados.contato()).isNull();
        assertThat(dados.modulo()).isNull();
        assertThat(dados.telaCodigo()).isEqualTo("VEN010");
        assertThat(dados.sintoma()).isEqualTo("Linha 1\n\nLinha 2");
        assertThat(dados.mensagemErro()).isNull();
        assertThat(dados.resolucao()).isEqualTo("Acertar o estoque");
    }

    @Test
    void todosOsProblemasNumaMensagemSo() {
        assertThatThrownBy(() -> DadosDoRegistro.conferir(" ", "outro", null, null, null, null, "X".repeat(41), null, null,
                null, null))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessage("Confira o registro: escreva o título; escolha o tipo (dúvida, erro ou alteração); o código da "
                        + "tela tem 41 caracteres (até 40).")
                .extracting("codigo").isEqualTo("REGISTRO_INVALIDO");
    }
}
