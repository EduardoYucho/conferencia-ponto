package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ServidorDeAnexos;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConfiguracaoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.FilaDeAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ParaOsTestes;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.fila.CoordenadorDaFila;
import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ConfiguracaoDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.FilaDaRedacao;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.MontarLinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.RedigirSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.BaixadorHttp;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.download.PoliticaDeRede;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.ExtratorQuadrosFfmpeg;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Do PDF ao texto com a IA local (banco, pasta e fila de verdade; modelo de mentira no papel da IA local): sem chave
 * do Gemini cadastrada, os arquivos vão pelo endereço no disco (nada é enviado), a redação sai em texto corrido com as
 * regras da habilidade e passa pela mesma conferência; com a placa de vídeo ocupada, as tarefas esperam sem gastar
 * tentativa.
 */
class IaLocalDoPdfAoTextoTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 'J', 'F', 'I', 'F', 0, 1, 2, 3};
    private static final byte[] OGG = "OggS\0\2\0\0\0\0\0\0opus".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PDF = "%PDF-1.7\nrelatorio".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] MP4 = "\0\0\0\u0018ftypisom\0\0\0\0video".getBytes(StandardCharsets.ISO_8859_1);

    @TempDir
    Path raiz;

    private final ServidorDeAnexos servidor = new ServidorDeAnexos();
    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();
    private final Tarefas tarefas = RepositoriosDeTeste.tarefas();
    private final Analises analises = RepositoriosDeTeste.analises();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final ChavesGemini chaves = RepositoriosDeTeste.chaves();
    private final ModeloDeMentira modelo = new ModeloDeMentira().local(true);
    private final ObjectMapper json = new ObjectMapper();
    private final ConfiguracaoDaAnalise configuracao = new ConfiguracaoDaAnalise("gemma4:12b", "gemma4:12b + whisper", 3,
            15 * 1024 * 1024, 32768, null, null);
    private final ConfiguracaoDaRedacao configuracaoDaRedacao = new ConfiguracaoDaRedacao("gemma4:12b", 3, 32768, null,
            null, Duration.ofHours(1), 10 * 1024 * 1024);
    private final FilaDeAnalises fila = new FilaDeAnalises(analises, tarefas, configuracao, chaves);
    private final FilaDaRedacao redacao = new FilaDaRedacao(saidas, tarefas, configuracaoDaRedacao);
    private final PublicadorDeProgresso publicador = new PublicadorDeProgresso(atendimentos, arquivos, evento -> { }, fila,
            redacao);
    private final List<CoordenadorDaFila> coordenadores = new ArrayList<>();
    /** Parado em AGORA; o teste da placa ocupada (que espera um pouco) usa um que anda. */
    private Clock relogio = Clock.fixed(AGORA, FUSO);
    private CofreAesGcm cofre;
    private Usuario maria;

    @BeforeEach
    void preparar() {
        PostgresDeTeste.jdbc().sql("DELETE FROM atendimento.tarefa").update();
        cofre = new CofreAesGcm(new ChaveMestraProperties(raiz.resolve("chave-mestra")));
        maria = new Usuario(PostgresDeTeste.novoUsuario(true), "maria", "maria", "hash", true, Acesso.usuario(),
                null, null, false);
        servidor.arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011620000.jpeg"), JPEG)
                .arquivo(PdfDigisacDeTeste.caminhoDoAnexo("1760011680000.oga"), OGG)
                .arquivo(PdfDigisacDeTeste.CAMINHO_DO_DOCUMENTO, PDF)
                .arquivo(PdfDigisacDeTeste.caminhoDoVideo("gravacao-tela.mp4"), MP4);
    }

    @AfterEach
    void parar() {
        coordenadores.forEach(CoordenadorDaFila::parar);
        servidor.close();
    }

    private ProcessarAtendimentos processamento() {
        return new ProcessarAtendimentos(atendimentos, arquivos, tarefas, publicador, relogio, 2, fila, redacao);
    }

    private void iniciarFila() {
        QuadrosDoVideo quadros = new QuadrosDoVideo(new PastaEmDisco(raiz), new ExtratorQuadrosFfmpeg(List.of(), Duration.ofSeconds(30)));
        BaixarAnexo baixar = new BaixarAnexo(arquivos, new PastaEmDisco(raiz),
                new BaixadorHttp(new PoliticaDeRede(List.of("127.0.0.1"), false, true, null), Duration.ofSeconds(2),
                        Duration.ofSeconds(1)),
                publicador, relogio, DataSize.ofMegabytes(10), DataSize.ofKilobytes(1), 4);
        var analisar = ParaOsTestes.analisar(analises, chaves, cofre, modelo, new PastaEmDisco(raiz), configuracao, relogio,
                quadros);
        var montar = new MontarLinhaDoTempo(atendimentos, analises, json, configuracaoDaRedacao, relogio);
        var redigir = new RedigirSaida(saidas, atendimentos, new ChamadasAoGemini(chaves, cofre, json, relogio, true), modelo,
                new PastaEmDisco(raiz), configuracaoDaRedacao, json, relogio, analises, quadros);
        CoordenadorDaFila c = new CoordenadorDaFila(tarefas, List.of(baixar, analisar, montar, redigir), processamento(),
                relogio, 8, 4, Duration.ofMinutes(15), Duration.ofMillis(100), Duration.ofMillis(1), Duration.ofMillis(5), true);
        coordenadores.add(c);
        c.iniciar();
    }

    private UUID criar() {
        GerenciarAtendimentos gerenciar = new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos, publicador,
                new PastaEmDisco(raiz), RepositoriosDeTeste.transacao(), relogio, DataSize.ofMegabytes(50), 30, 180);
        byte[] pdf = PdfDigisacDeTeste.exemplo("20261009T120000Z", servidor.base()).bytes();
        return gerenciar.receberPdf(maria, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
    }

    private void esperarAte(UUID id, SituacaoDoAtendimento esperada) {
        await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(50))
                .until(() -> atendimentos.estado(id).orElseThrow().situacao() == esperada);
    }

    private EstadoDaSaida saida(UUID id, String tipo) {
        return redacao.estados(id).stream().filter(s -> s.tipo().equals(tipo)).findFirst().orElseThrow();
    }

    private List<PedidoAoModelo> redacoes() {
        return modelo.geracoes().stream().map(ModeloDeMentira.Geracao::pedido).filter(p -> p.esquemaJson().isBlank()).toList();
    }

    @Test
    void semChaveDoGeminiOsArquivosVaoPeloDiscoEOResumoSaiEmTextoCorridoConferido() {
        UUID id = criar();
        iniciarFila();

        ProgressoView inicio = processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        assertThat(inicio.semChaveGemini()).as("a IA local não precisa de chave").isFalse();

        esperarAte(id, SituacaoDoAtendimento.PRONTO);
        assertThat(saida(id, "resumo").situacao()).isEqualTo("pronta");
        assertThat(modelo.enviados()).as("nada foi enviado: a IA local lê do disco").isEmpty();
        List<PedidoAoModelo> analisesPedidas = modelo.geracoes().stream().map(ModeloDeMentira.Geracao::pedido)
                .filter(p -> p.midia() != null).toList();
        assertThat(analisesPedidas).hasSize(4).allSatisfy(p -> {
            assertThat(p.midia().uri()).startsWith("file:");
            assertThat(p.midia().dados()).isNull();
        });
        assertThat(modelo.geracoes()).allSatisfy(g -> assertThat(g.chave()).isEqualTo(ChamadasAoGemini.CHAVE_LOCAL));

        PedidoAoModelo pedido = redacoes().get(0);
        assertThat(pedido.instrucaoDoSistema()).contains("O material vem em texto").contains("RESUMO (\"resumo\")")
                .doesNotContain("Atendimento_<chamado>.pdf");
        assertThat(pedido.instrucao()).contains("[M1]").contains("Tela de pedidos com erro")
                .contains("O sistema travou na emissão da nota").contains("Imagens que o texto pode mostrar")
                .contains("Escreva agora o \"resumo\"").contains("Assunto: Digisac: Loja Exemplo - Maria : ")
                .doesNotContain("123 456 789");

        SaidaView texto = new GerenciarSaidas(atendimentos, saidas, new PastaEmDisco(raiz), configuracaoDaRedacao, json)
                .detalhe(maria, id, saida(id, "resumo").id());
        assertThat(texto.modelo()).isEqualTo("gemma4:12b (IA local)");
        assertThat(texto.assunto()).endsWith(" : Erro ao emitir a nota fiscal na tela de vendas");
        assertThat(texto.html()).contains("<strong>Imagem 1</strong>: tela Pedidos com o erro").doesNotContain("<ol>")
                .contains("[omitido]").doesNotContain("123 456 789").doesNotContain("Imagem 2");
        assertThat(texto.avisos()).noneMatch(a -> a.contains("sem fonte"))
                .anyMatch(a -> a.contains("imagem citada")); // a que não existe (A9) sai do texto, com aviso
    }

    @Test
    void oChamadoDeErroEmTextoSaiComAsPartesNaOrdem() {
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.DEV_ERRO));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);

        SaidaView texto = new GerenciarSaidas(atendimentos, saidas, new PastaEmDisco(raiz), configuracaoDaRedacao, json)
                .detalhe(maria, id, saida(id, "dev_erro").id());
        assertThat(texto.telaFuncionalidade()).isEqualTo("Pedidos (VEN010)");
        assertThat(texto.html()).containsSubsequence("Caminho da tela", "Relato do problema", "Passos para reproduzir",
                "Resultado obtido", "Causa", "Pedido ao desenvolvimento");
        assertThat(texto.html()).contains("Causa não identificada.");
        assertThat(redacoes().get(0).instrucao()).contains("Escreva agora o chamado de \"erro\"")
                .contains("Tela/Funcionalidade:");
    }

    @Test
    void comAPlacaDeVideoOcupadaAsTarefasEsperamSemGastarTentativa() {
        // mais vezes ocupada que as 3 tentativas da configuração: se gastasse tentativa, a análise falharia de vez
        for (int i = 0; i < 6; i++) {
            modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.PLACA_OCUPADA, "a placa de vídeo está ocupada",
                    Duration.ofMillis(5), 0));
        }
        relogio = Clock.offset(Clock.system(FUSO), Duration.between(Instant.now(), AGORA)); // anda, a partir de AGORA
        UUID id = criar();
        iniciarFila();

        processamento().processar(maria, id, Set.of(TipoDeSaida.RESUMO));
        esperarAte(id, SituacaoDoAtendimento.PRONTO);

        assertThat(saida(id, "resumo").situacao()).isEqualTo("pronta");
        assertThat(PostgresDeTeste.jdbc().sql("SELECT count(*) FROM atendimento.tarefa WHERE atendimento_id = :id "
                        + "AND situacao = 'falhou'").param("id", id).query(Long.class).single())
                .as("nenhuma tarefa falhou").isZero();
    }
}
