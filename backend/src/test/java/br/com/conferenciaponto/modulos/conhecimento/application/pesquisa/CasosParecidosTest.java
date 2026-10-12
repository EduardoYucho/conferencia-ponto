package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.ParaOsTestesDosVetores;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.RelogioDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.VetoresDaBase;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.VetoresDeMentira;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O aviso de caso parecido: pelo significado acima do limiar e pela mesma tela, sem os do próprio atendimento. */
class CasosParecidosTest {

    private static final int D = ParaOsTestesDosVetores.DIMENSAO;

    private final ParaOsTestesDosVetores base = new ParaOsTestesDosVetores();
    private final VetoresDeMentira gerador = new VetoresDeMentira();
    private final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-12T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private VetoresDaBase vetores;
    private CasosParecidos parecidos;
    private UUID autor;
    private Usuario quem;
    private final String codigo = "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

    private static float[] direcao(float x, float y) {
        float[] v = new float[D];
        v[0] = x;
        v[1] = y;
        return v;
    }

    @BeforeEach
    void preparar() {
        autor = ParaOsTestesDosVetores.autor();
        quem = new Usuario(ParaOsTestesDosVetores.autor(), "quem", "quem", "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
        gerador.comChave(autor, quem.id())
                .fixo("Pedido com desconto não grava", direcao(1, 0))
                .fixo("Desconto acima do limite trava o pedido", direcao(0.9f, 0.44f))
                .fixo("Impressora não imprime", direcao(0, 1))
                .fixo("O caso novo do desconto", direcao(1, 0.05f));
        vetores = new VetoresDaBase(RepositoriosDaBase.vetores(), gerador.provedor(), base.configuracao(0.6, 0.85), relogio);
        parecidos = new CasosParecidos(base.registros, vetores, base.configuracao(0.6, 0.85));
    }

    @Test
    void peloSignificadoEPelaMesmaTelaSemODoProprioAtendimento() {
        UUID atendimento = UUID.randomUUID();
        UUID igual = base.registro(autor, "Pedido com desconto não grava", "Erro.", null, null, null);
        UUID parecido = base.registro(autor, "Desconto acima do limite trava o pedido", "Erro.", null, null, null);
        UUID outroAssunto = base.registro(autor, "Impressora não imprime", "Nada sai.", null, codigo, null);
        UUID doProprio = base.registro(autor, "Pedido com desconto não grava", "Erro.", null, null, atendimento);
        vetores.carregar();
        vetores.gerarPendentes();

        CasosParecidos.Resultado resultado = parecidos.procurar(quem,
                new CasosParecidos.Pedido("O caso novo do desconto", "O pedido não grava.\nnão identificado", codigo, atendimento));

        assertThat(resultado.porSignificado()).isTrue();
        assertThat(resultado.aviso()).isNull();
        assertThat(resultado.registros()).extracting(CasosParecidos.Parecido::id).containsExactly(igual, parecido, outroAssunto)
                .doesNotContain(doProprio);
        assertThat(resultado.registros().get(0).similaridade()).isGreaterThan(0.99);
        assertThat(resultado.registros().get(0).chamadoDev()).startsWith("OTRS");
        assertThat(resultado.registros().get(0).situacaoRotulo()).isEqualTo("Enviado ao desenvolvimento");
        CasosParecidos.Parecido daTela = resultado.registros().get(2);
        assertThat(daTela.similaridade()).as("abaixo do limiar: entrou pela tela").isNull();
        assertThat(daTela.mesmaTela()).isTrue();
        assertThat(gerador.pedidosDe(quem.id())).singleElement()
                .satisfies(p -> assertThat(p.texto()).isEqualTo("title: O caso novo do desconto | text: O pedido não grava."));
    }

    @Test
    void semChaveSoAMesmaTelaComAviso() {
        UUID daTela = base.registro(autor, "Impressora não imprime", "Nada sai.", null, codigo, null);
        base.registro(autor, "Pedido com desconto não grava", "Erro.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();
        gerador.semChave(quem.id());

        CasosParecidos.Resultado resultado = parecidos.procurar(quem,
                new CasosParecidos.Pedido("O caso novo do desconto", null, codigo.toLowerCase(), null));

        assertThat(resultado.porSignificado()).isFalse();
        assertThat(resultado.aviso()).startsWith("Só a pesquisa exata");
        assertThat(resultado.registros()).extracting(CasosParecidos.Parecido::id).containsExactly(daTela);
        assertThat(parecidos.procurar(quem, new CasosParecidos.Pedido(null, " ", null, null)).registros()).isEmpty();
    }
}
