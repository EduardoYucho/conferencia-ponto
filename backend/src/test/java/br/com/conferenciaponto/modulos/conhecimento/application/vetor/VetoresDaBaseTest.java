package br.com.conferenciaponto.modulos.conhecimento.application.vetor;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.TextoDoVetor;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Os vetores da base no banco de verdade: os pendentes gerados com a chave do autor (em segundo plano, com espera
 * quando falha), o índice em memória, a edição que muda o texto, a troca de modelo e o vetor da pesquisa.
 */
class VetoresDaBaseTest {

    private final ParaOsTestesDosVetores base = new ParaOsTestesDosVetores();
    private final VetoresDeMentira gerador = new VetoresDeMentira();
    private final RelogioDeTeste relogio = new RelogioDeTeste(Instant.parse("2026-10-12T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
    private final JdbcClient jdbc = PostgresDeTeste.jdbc();

    private VetoresDaBase vetores(String modelo) {
        return new VetoresDaBase(RepositoriosDaBase.vetores(), gerador.provedor(),
                new ConfiguracaoDosVetores(modelo, ParaOsTestesDosVetores.DIMENSAO, 0.5, 30, 50, 5, 0.75), relogio);
    }

    private long vetoresNoBanco(UUID registro, String modelo) {
        return jdbc.sql("SELECT count(*) FROM conhecimento.vetor WHERE registro_id = ? AND modelo = ?").params(registro, modelo)
                .query(Long.class).single();
    }

    @Test
    void geraOsPendentesComAChaveDoAutorEColocaNoIndice() {
        UUID autor = ParaOsTestesDosVetores.autor();
        UUID semChave = ParaOsTestesDosVetores.autor();
        gerador.comChave(autor);
        UUID nota = base.registro(autor, "Nota duplicada ao emitir", "A nota sai duas vezes.", null, null, null);
        UUID outro = base.registro(semChave, "Relatório lento", "Demora para abrir.", null, null, null);
        VetoresDaBase vetores = vetores(base.modelo);
        vetores.carregar();

        VetoresDaBase.Rodada rodada = vetores.gerarPendentes();

        assertThat(rodada.gerados()).isEqualTo(1);
        assertThat(vetores.tem(nota)).isTrue();
        assertThat(vetores.tem(outro)).as("o autor não tem chave: fica pendente").isFalse();
        assertThat(vetoresNoBanco(nota, base.modelo)).isEqualTo(1);
        assertThat(gerador.pedidosDe(autor)).singleElement().satisfies(p -> {
            assertThat(p.texto()).isEqualTo("title: Nota duplicada ao emitir | text: A nota sai duas vezes.");
            assertThat(p.modelo()).isEqualTo(base.modelo);
            assertThat(p.dimensao()).isEqualTo(ParaOsTestesDosVetores.DIMENSAO);
        });

        // o autor cadastrou a chave: depois da espera, o pendente sai
        gerador.comChave(semChave);
        assertThat(vetores.gerarPendentes().gerados()).as("ainda na espera").isZero();
        relogio.adiantar(Duration.ofMinutes(11));
        vetores.gerarPendentes();
        assertThat(vetores.tem(outro)).isTrue();
        assertThat(gerador.pedidosDe(autor)).as("o que já tem vetor não é refeito").hasSize(1);

        // um índice novo (a subida do sistema) lê do banco
        VetoresDaBase depoisDeReiniciar = vetores(base.modelo);
        depoisDeReiniciar.carregar();
        assertThat(depoisDeReiniciar.tem(nota)).isTrue();
        assertThat(depoisDeReiniciar.tem(outro)).isTrue();
    }

    @Test
    void geminiForaParaARodadaEEsperaMaisACadaFalha() {
        UUID autor = ParaOsTestesDosVetores.autor();
        gerador.comChave(autor);
        UUID registro = base.registro(autor, "Erro ao gravar", "Não grava.", null, null, null);
        VetoresDaBase vetores = vetores(base.modelo);
        vetores.carregar();
        gerador.falhar(new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "fora"))
                .falhar(new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "fora"));

        assertThat(vetores.gerarPendentes().falharam()).isEqualTo(1);
        relogio.adiantar(Duration.ofSeconds(30));
        assertThat(vetores.gerarPendentes().falharam()).as("pausado por 1 min").isZero();
        relogio.adiantar(Duration.ofSeconds(31));
        assertThat(vetores.gerarPendentes().falharam()).isEqualTo(1);
        relogio.adiantar(Duration.ofSeconds(90));
        assertThat(vetores.gerarPendentes().gerados()).as("agora pausado por 2 min").isZero();
        relogio.adiantar(Duration.ofSeconds(31));
        assertThat(vetores.gerarPendentes().gerados()).isEqualTo(1);
        assertThat(vetores.tem(registro)).isTrue();
    }

    @Test
    void edicaoQueMudaOTextoDeixaOVetorParaRefazer() {
        UUID autor = ParaOsTestesDosVetores.autor();
        gerador.comChave(autor);
        UUID registro = base.registro(autor, "Erro ao gravar", "Não grava.", null, null, null);
        VetoresDaBase vetores = vetores(base.modelo);
        vetores.carregar();
        vetores.gerarPendentes();

        vetores.textoMudou(registro, "Erro ao gravar", "Não grava.", null, null, null);
        assertThat(vetores.tem(registro)).as("mesmo texto: o vetor fica").isTrue();

        vetores.textoMudou(registro, "Erro ao gravar o pedido", "Não grava.", null, null, null);
        // o vetor guardado sai na hora e um novo é pedido logo depois, em segundo plano (sem esperar a rodada); o teste
        // espera o novo em vez de olhar o meio do caminho, que depende da velocidade da máquina
        await().atMost(10, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                .until(() -> vetores.tem(registro) && gerador.pedidosDe(autor).size() == 2);
        assertThat(vetoresNoBanco(registro, base.modelo)).as("um só, o refeito").isEqualTo(1);
    }

    @Test
    void trocarOModeloRecalculaEApagaOsVetoresDoAnterior() {
        UUID autor = ParaOsTestesDosVetores.autor();
        gerador.comChave(autor);
        UUID registro = base.registro(autor, "Erro ao gravar", "Não grava.", null, null, null);
        VetoresDaBase antigo = vetores(base.modelo);
        antigo.carregar();
        antigo.gerarPendentes();
        String novoModelo = base.modelo + "-novo";

        VetoresDaBase novo = vetores(novoModelo);
        novo.carregar();
        assertThat(novo.tem(registro)).as("o índice do modelo novo sobe vazio").isFalse();
        novo.gerarPendentes();

        assertThat(novo.tem(registro)).isTrue();
        assertThat(vetoresNoBanco(registro, novoModelo)).isEqualTo(1);
        assertThat(vetoresNoBanco(registro, base.modelo)).isZero();
    }

    @Test
    void vetorDaPesquisaComAChaveDeQuemPesquisaEGuardado() {
        UUID quem = ParaOsTestesDosVetores.autor();
        VetoresDaBase vetores = vetores(base.modelo);
        vetores.carregar();

        VetoresDaBase.Consulta semChave = vetores.consulta(quem, TextoDoVetor.consulta("nota duplicada"));
        assertThat(semChave.ativa()).isFalse();
        assertThat(semChave.aviso()).isEqualTo("Só a pesquisa exata: cadastre sua chave do Gemini (Atendimentos → Minha chave "
                + "do Gemini) para usar a pesquisa por significado.");

        gerador.comChave(quem);
        assertThat(vetores.consulta(quem, TextoDoVetor.consulta("nota duplicada")).ativa()).isTrue();
        assertThat(vetores.consulta(quem, TextoDoVetor.consulta("nota duplicada")).ativa()).isTrue();
        assertThat(gerador.pedidosDe(quem)).as("a mesma pesquisa não é calculada de novo").hasSize(1);

        gerador.falhar(new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "fora"));
        VetoresDaBase.Consulta fora = vetores.consulta(quem, TextoDoVetor.consulta("outra coisa"));
        assertThat(fora.ativa()).isFalse();
        assertThat(fora.aviso()).contains("o Gemini não respondeu agora");

        VetoresDaBase semGerador = new VetoresDaBase(RepositoriosDaBase.vetores(), VetoresDeMentira.nenhum(),
                base.configuracao(0.5, 0.75), relogio);
        assertThat(semGerador.consulta(quem, "x").aviso()).contains("não está disponível");
        assertThat(semGerador.gerarPendentes().gerados()).isZero();
    }

    @Test
    void semelhantesPeloIndice() {
        UUID autor = ParaOsTestesDosVetores.autor();
        gerador.comChave(autor);
        UUID nota = base.registro(autor, "Nota fiscal duplicada", "A nota fiscal sai duplicada na emissão.", null, null, null);
        UUID relatorio = base.registro(autor, "Relatório de vendas lento", "O relatório de vendas demora.", null, null, null);
        VetoresDaBase vetores = vetores(base.modelo);
        vetores.carregar();
        vetores.gerarPendentes();

        VetoresDaBase.Consulta consulta = vetores.consulta(autor, TextoDoVetor.consulta("nota fiscal duplicada"));
        var achados = vetores.semelhantes(consulta.vetor(), 0.3, 10, null);

        assertThat(achados).extracting(Semelhante::registroId).containsExactly(nota);
        assertThat(vetores.semelhantes(consulta.vetor(), 0.0, 10, null)).extracting(Semelhante::registroId).startsWith(nota)
                .contains(relatorio);
        vetores.esquecer(nota);
        assertThat(vetores.semelhantes(consulta.vetor(), 0.3, 10, null)).isEmpty();
    }
}
