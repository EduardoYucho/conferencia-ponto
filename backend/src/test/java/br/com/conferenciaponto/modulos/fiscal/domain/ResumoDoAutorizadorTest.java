package br.com.conferenciaponto.modulos.fiscal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.servico;
import static org.assertj.core.api.Assertions.assertThat;

class ResumoDoAutorizadorTest {

    @Test
    @DisplayName("Todos os serviços normais: o autorizador está normal")
    void todosNormais() {
        List<Leitura> resumo = ResumoDoAutorizador.resumir(List.of(
                servico(Documento.NFE, "SVRS", "Autorização", Situacao.NORMAL),
                servico(Documento.NFE, "SVRS", "Eventos", Situacao.NORMAL)));
        assertThat(resumo).singleElement().satisfies(l -> {
            assertThat(l.alvo()).isEqualTo(Alvo.geral(Documento.NFE, "SVRS"));
            assertThat(l.situacao()).isEqualTo(Situacao.NORMAL);
            assertThat(l.detalhe()).isEqualTo("Todos os serviços normais");
        });
    }

    @Test
    @DisplayName("A autorização fora derruba o autorizador; outro serviço fora o deixa instável, com o serviço no detalhe")
    void autorizacaoForaEOutroForaInstavel() {
        List<Leitura> autorizacaoFora = ResumoDoAutorizador.resumir(List.of(
                servico(Documento.NFE, "RS", "Autorização", Situacao.FORA),
                servico(Documento.NFE, "RS", "Eventos", Situacao.NORMAL)));
        assertThat(autorizacaoFora).singleElement().satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(Situacao.FORA);
            assertThat(l.detalhe()).contains("Autorização");
        });

        List<Leitura> eventosFora = ResumoDoAutorizador.resumir(List.of(
                servico(Documento.NFE, "RS", "Autorização", Situacao.NORMAL),
                servico(Documento.NFE, "RS", "Eventos", Situacao.FORA)));
        assertThat(eventosFora).singleElement().satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(Situacao.INSTAVEL);
            assertThat(l.detalhe()).startsWith("Eventos");
        });
    }

    @Test
    @DisplayName("Tudo sem informação fica sem informação; um serviço só vira o próprio resumo; cada autorizador separado")
    void semInformacaoUmServicoEVarios() {
        List<Leitura> resumo = ResumoDoAutorizador.resumir(List.of(
                servico(Documento.CTE, "SVRS", "Autorização", Situacao.SEM_INFORMACAO),
                servico(Documento.CTE, "SVRS", "Eventos", Situacao.SEM_INFORMACAO),
                LeiturasDeTeste.conexao(Documento.CTE, "MG", Situacao.INSTAVEL, 4200)));
        assertThat(resumo).hasSize(2);
        assertThat(resumo).filteredOn(l -> l.alvo().autorizador().equals("SVRS")).singleElement()
                .satisfies(l -> assertThat(l.situacao()).isEqualTo(Situacao.SEM_INFORMACAO));
        assertThat(resumo).filteredOn(l -> l.alvo().autorizador().equals("MG")).singleElement().satisfies(l -> {
            assertThat(l.situacao()).isEqualTo(Situacao.INSTAVEL);
            assertThat(l.tempoMs()).isEqualTo(4200);
            assertThat(l.origem()).isEqualTo(Origem.TESTE_DE_CONEXAO);
        });
    }
}
