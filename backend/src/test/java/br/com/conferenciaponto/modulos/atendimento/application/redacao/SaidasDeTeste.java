package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/** Uma saída já redigida (pronta) no banco, para os testes de outros pacotes (ex.: a confirmação na base). */
public final class SaidasDeTeste {

    private SaidasDeTeste() {
    }

    /** Cria a próxima versão do tipo e grava o texto como se o Gemini tivesse acabado de redigir. */
    public static UUID pronta(Saidas saidas, UUID atendimento, TipoDeSaida tipo, TextoRedigido texto, List<NovaImagem> imagens,
                              Instant agora) {
        UUID saida = saidas.criar(atendimento, tipo, EnumSet.allOf(SituacaoDaSaida.class), agora).orElseThrow();
        saidas.salvarRedacao(saida, EstruturaDaSaida.escrever(new ObjectMapper(), texto), MontagemDoHtml.montar(texto.blocos()),
                texto.assunto(), "m", 1, 100, 20, imagens);
        return saida;
    }
}
