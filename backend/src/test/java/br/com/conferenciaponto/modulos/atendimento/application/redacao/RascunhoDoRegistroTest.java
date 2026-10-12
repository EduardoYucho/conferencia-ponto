package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RascunhoDoRegistroTest {

    @Test
    void oTituloSaiDoAssuntoSemOPrefixo() {
        assertThat(RascunhoDoRegistro.titulo("Digisac: Maria : Erro ao gravar o pedido", "Maria")).isEqualTo("Erro ao gravar o pedido");
        assertThat(RascunhoDoRegistro.titulo("Digisac: Maria Souza : Erro: estoque", "Outra")).isEqualTo("Erro: estoque");
        assertThat(RascunhoDoRegistro.titulo("Erro ao gravar", "Maria")).isEqualTo("Erro ao gravar");
        assertThat(RascunhoDoRegistro.titulo(null, "Maria")).isNull();
    }

    @Test
    void naoIdentificadoFicaEmBranco() {
        assertThat(RascunhoDoRegistro.valor("não identificado")).isNull();
        assertThat(RascunhoDoRegistro.valor(" Não identificado. ")).isNull();
        assertThat(RascunhoDoRegistro.valor("  ")).isNull();
        assertThat(RascunhoDoRegistro.valor(" Padaria ")).isEqualTo("Padaria");
    }

    @Test
    void oTipoDoRegistroPeloTipoDoTexto() {
        assertThat(RascunhoDoRegistro.tipo(TipoDeSaida.RESUMO)).isEqualTo("duvida");
        assertThat(RascunhoDoRegistro.tipo(TipoDeSaida.DEV_ERRO)).isEqualTo("erro");
        assertThat(RascunhoDoRegistro.tipo(TipoDeSaida.DEV_ALTERACAO)).isEqualTo("alteracao");
    }
}
