package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ResumoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ConversaLida;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeitorConversaDigisac;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** atendimento.atendimento e atendimento.arquivo no PostgreSQL temporário, com a conversa do PDF sintético. */
class AtendimentosJdbcTest {

    private static Mascaramento.Resultado lida;

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final AtendimentosJdbc atendimentos = new AtendimentosJdbc(jdbc, new ObjectMapper());
    private final Instant agora = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeAll
    static void lerOPdf(@TempDir Path pasta) throws IOException {
        Path pdf = Files.write(pasta.resolve("conversa.pdf"), PdfDigisacDeTeste.exemplo().bytes());
        ConversaLida conversa = new LeitorConversaDigisac(ZoneId.of("America/Sao_Paulo")).ler(new ExtratorPdfBox().extrair(pdf));
        lida = new Mascaramento().aplicar(conversa);
    }

    private NovoAtendimento novo(UUID usuario, Instant criadoEm) {
        return new NovoAtendimento(UUID.randomUUID(), usuario, lida.conversa(), lida.omitidos(), LeitorConversaDigisac.VERSAO,
                criadoEm, criadoEm.plus(30, ChronoUnit.DAYS), criadoEm.plus(180, ChronoUnit.DAYS));
    }

    @Test
    void guardaELeOAtendimentoComAConversaEOsAnexos() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        NovoAtendimento novo = novo(maria, agora);

        atendimentos.salvarNovo(novo);
        AtendimentoGuardado guardado = atendimentos.buscar(novo.id(), maria).orElseThrow();

        ResumoDoAtendimento resumo = guardado.resumo();
        assertThat(resumo.chamado()).isEqualTo(PdfDigisacDeTeste.CHAMADO);
        assertThat(resumo.contato()).isEqualTo("Loja Exemplo - Maria");
        assertThat(resumo.inicio()).isEqualTo(Instant.parse("2026-10-09T11:00:00Z"));
        assertThat(resumo.situacao()).isEqualTo(SituacaoDoAtendimento.NOVO);
        assertThat(resumo.criadoEm()).isEqualTo(agora);
        assertThat(resumo.mensagens()).isEqualTo(14);
        assertThat(resumo.anexos()).isEqualTo(4);
        assertThat(resumo.linksValidosAte()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));

        assertThat(guardado.versaoLeitor()).isEqualTo(LeitorConversaDigisac.VERSAO);
        assertThat(guardado.apagarArquivosEm()).isEqualTo(agora.plus(30, ChronoUnit.DAYS));
        assertThat(guardado.conversa().itens()).isEqualTo(lida.conversa().itens());
        assertThat(guardado.conversa().cabecalho()).isEqualTo(lida.conversa().cabecalho());
        assertThat(guardado.conversa().omitidos()).isEqualTo(lida.omitidos());

        assertThat(guardado.arquivos()).extracting(a -> a.origem() + ":" + a.ordem() + ":" + a.categoria())
                .containsExactly("anexo_conversa:1:IMAGEM", "anexo_conversa:2:AUDIO", "anexo_conversa:3:DOCUMENTO",
                        "anexo_conversa:4:VIDEO");
        assertThat(guardado.arquivos()).allSatisfy(a -> {
            assertThat(a.situacao()).isEqualTo("aguardando");
            assertThat(a.urlValidaAte()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));
            ItemDaConversa mensagem = guardado.conversa().itens().get(a.mensagemOrdem() - 1);
            assertThat(a.momento()).isEqualTo(mensagem.momento());
        });
        assertThat(guardado.arquivos().get(2).nome()).isEqualTo("Relatório de vendas.pdf");
        assertThat(guardado.arquivos().get(2).categoria()).isEqualTo(CategoriaDoArquivo.DOCUMENTO);
    }

    @Test
    void oLinkFicaSoNoBancoParaODownloadEAConversaGuardadaJaEstaMascarada() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        NovoAtendimento novo = novo(maria, agora);

        atendimentos.salvarNovo(novo);

        List<String> urls = jdbc.sql("SELECT url_download FROM atendimento.arquivo WHERE atendimento_id = ? ORDER BY ordem")
                .param(novo.id()).query(String.class).list();
        assertThat(urls).hasSize(4).allMatch(u -> u.startsWith(PdfDigisacDeTeste.HOST) && u.contains("X-Amz-Signature"));
        String conversa = jdbc.sql("SELECT conversa::text FROM atendimento.atendimento WHERE id = ?")
                .param(novo.id()).query(String.class).single();
        assertThat(conversa).contains(Mascaramento.OMITIDO).doesNotContain("4815162342").doesNotContain("4821")
                .doesNotContain("12 345 678").doesNotContain("X-Amz").doesNotContain("99999-0000");
        String fonte = jdbc.sql("SELECT string_agg(DISTINCT momento_fonte, ',') FROM atendimento.arquivo WHERE atendimento_id = ?")
                .param(novo.id()).query(String.class).single();
        assertThat(fonte).isEqualTo("mensagem");
    }

    @Test
    void listaOsDoUsuarioDoMaisNovoParaOMaisAntigo() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        UUID joao = PostgresDeTeste.novoUsuario(true);
        NovoAtendimento antigo = novo(maria, agora.minus(2, ChronoUnit.DAYS));
        NovoAtendimento recente = novo(maria, agora);
        NovoAtendimento doJoao = novo(joao, agora);
        atendimentos.salvarNovo(antigo);
        atendimentos.salvarNovo(recente);
        atendimentos.salvarNovo(doJoao);

        assertThat(atendimentos.doUsuario(maria)).extracting(ResumoDoAtendimento::id).containsExactly(recente.id(), antigo.id());
        assertThat(atendimentos.doChamado(maria, PdfDigisacDeTeste.CHAMADO)).map(ResumoDoAtendimento::id).contains(recente.id());
        assertThat(atendimentos.doChamado(maria, "outro")).isEmpty();
    }

    @Test
    void oAtendimentoDeOutraPessoaNaoExisteParaQuemPergunta() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        UUID joao = PostgresDeTeste.novoUsuario(true);
        NovoAtendimento daMaria = novo(maria, agora);
        atendimentos.salvarNovo(daMaria);

        assertThat(atendimentos.buscar(daMaria.id(), joao)).isEmpty();
        assertThat(atendimentos.existe(daMaria.id(), joao)).isFalse();
        assertThat(atendimentos.doUsuario(joao)).isEmpty();
        assertThat(atendimentos.doChamado(joao, PdfDigisacDeTeste.CHAMADO)).isEmpty();
        assertThat(atendimentos.apagar(daMaria.id(), joao)).isFalse();
        assertThat(atendimentos.existe(daMaria.id(), maria)).isTrue();
    }

    @Test
    void apagarLevaOsAnexosJunto() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        NovoAtendimento novo = novo(maria, agora);
        atendimentos.salvarNovo(novo);

        assertThat(atendimentos.apagar(novo.id(), maria)).isTrue();

        assertThat(atendimentos.buscar(novo.id(), maria)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM atendimento.arquivo WHERE atendimento_id = ?").param(novo.id())
                .query(Long.class).single()).isZero();
    }
}
