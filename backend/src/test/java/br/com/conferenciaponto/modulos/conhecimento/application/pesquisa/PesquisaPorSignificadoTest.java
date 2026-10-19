package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
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

/**
 * A pesquisa com as duas metades: o que a exata não acha (outras palavras, mesmo assunto) entra pelo significado,
 * marcado e com a similaridade; o que bate nas duas sobe; os filtros valem para os dois; sem chave, só a exata.
 */
class PesquisaPorSignificadoTest {

    // vetores fixos com ordem conhecida (só a direção importa: o índice normaliza)
    private static final int D = ParaOsTestesDosVetores.DIMENSAO;

    private final ParaOsTestesDosVetores base = new ParaOsTestesDosVetores();
    private final VetoresDeMentira gerador = new VetoresDeMentira();
    private final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-12T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private VetoresDaBase vetores;
    private PesquisarNaBase pesquisar;
    private UUID autor;
    private Usuario quem;

    private static float[] direcao(float x, float y) {
        float[] v = new float[D];
        v[0] = x;
        v[1] = y;
        return v;
    }

    @BeforeEach
    void preparar() {
        autor = ParaOsTestesDosVetores.autor();
        quem = new Usuario(ParaOsTestesDosVetores.autor(), "quem", "quem", "hash", true, Acesso.usuario(), null, null, false);
        gerador.comChave(autor, quem.id())
                .fixo("Documento fiscal emitido em duplicidade", direcao(1, 0.1f))
                .fixo("Relatório de comissões lento", direcao(0, 1))
                .fixo("Nota duplicada no caixa", direcao(0.7f, 0.7f))
                .fixo("query: nota duplicada", direcao(1, 0));
        vetores = new VetoresDaBase(RepositoriosDaBase.vetores(), gerador.provedor(), base.configuracao(0.6, 0.75), relogio);
        pesquisar = new PesquisarNaBase(base.registros, relogio, vetores, base.configuracao(0.6, 0.75));
    }

    private ResultadoDaPesquisa pesquisa(String q, String tipo) {
        return pesquisar.pesquisar(quem, new FiltrosDaPesquisa(q, tipo, null, null, null, autor, null, null, false, 1));
    }

    @Test
    void outrasPalavrasMesmoAssunto() {
        UUID duplicidade = base.registro(autor, "Documento fiscal emitido em duplicidade", "Saem dois documentos.", null, null, null);
        UUID comissoes = base.registro(autor, "Relatório de comissões lento", "Demora.", null, null, null);
        UUID caixa = base.registro(autor, "Nota duplicada no caixa", "A nota sai duplicada.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();

        ResultadoDaPesquisa resultado = pesquisa("nota duplicada", null);

        assertThat(resultado.significado().ativa()).isTrue();
        assertThat(resultado.significado().aviso()).isNull();
        assertThat(resultado.registros()).as("o que bate nas duas sobe").extracting(RegistroEncontrado::id)
                .containsExactly(caixa, duplicidade).doesNotContain(comissoes);
        RegistroEncontrado nasDuas = resultado.registros().get(0);
        assertThat(nasDuas.porSignificado()).as("o texto bateu").isFalse();
        assertThat(nasDuas.similaridade()).isBetween(0.6, 0.8);
        assertThat(nasDuas.titulo()).anySatisfy(p -> assertThat(p.destaque()).isTrue());
        RegistroEncontrado soPeloSignificado = resultado.registros().get(1);
        assertThat(soPeloSignificado.porSignificado()).as("nada do texto bateu").isTrue();
        assertThat(soPeloSignificado.similaridade()).isGreaterThan(0.99);
    }

    @Test
    void osFiltrosValemParaOSignificadoTambem() {
        base.registro(autor, "Documento fiscal emitido em duplicidade", "Saem dois documentos.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();

        assertThat(pesquisa("nota duplicada", "duvida").registros()).isEmpty();
        assertThat(pesquisar.pesquisar(quem, new FiltrosDaPesquisa("nota duplicada", null, null, null, null,
                ParaOsTestesDosVetores.autor(), null, null, false, 1)).registros()).as("outro autor").isEmpty();
    }

    @Test
    void semChaveFicaSoAExataComAviso() {
        UUID duplicidade = base.registro(autor, "Documento fiscal emitido em duplicidade", "Saem dois documentos.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();
        gerador.semChave(quem.id());

        ResultadoDaPesquisa resultado = pesquisa("nota duplicada", null);

        assertThat(resultado.significado().ativa()).isFalse();
        assertThat(resultado.significado().aviso()).startsWith("Só a pesquisa exata: cadastre sua chave do Gemini");
        assertThat(resultado.registros()).extracting(RegistroEncontrado::id).doesNotContain(duplicidade);
        assertThat(pesquisa("documento fiscal", null).registros()).extracting(RegistroEncontrado::id).containsExactly(duplicidade);
    }

    @Test
    void geminiForaNaHoraFicaSoAExata() {
        base.registro(autor, "Documento fiscal emitido em duplicidade", "Saem dois documentos.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();
        gerador.falhar(new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "fora"));

        ResultadoDaPesquisa resultado = pesquisa("nota duplicada", null);

        assertThat(resultado.significado().ativa()).isFalse();
        assertThat(resultado.significado().aviso()).contains("o Gemini não respondeu agora");
        assertThat(resultado.registros()).isEmpty();
    }

    @Test
    void pendentesContados() {
        UUID semChave = ParaOsTestesDosVetores.autor();
        base.registro(semChave, "Documento fiscal emitido em duplicidade", "Saem dois documentos.", null, null, null);
        vetores.carregar();
        vetores.gerarPendentes();

        assertThat(pesquisa("qualquer coisa", null).significado().pendentes()).isPositive();
        assertThat(pesquisa(null, null).significado()).as("sem texto, sem pesquisa por significado").isNull();
    }
}
