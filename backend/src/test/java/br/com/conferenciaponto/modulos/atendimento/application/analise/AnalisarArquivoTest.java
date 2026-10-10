package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.ModeloDeMentira;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.Sha256;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto.CofreAesGcm;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** A tarefa "analisar" com o banco de verdade, a pasta de verdade e um Gemini de mentira. */
class AnalisarArquivoTest {

    private static final String CHAVE = "AQ.Ab8RN6Kc-chave_de-teste.0123456789wxyz";
    private static final Instant AGORA = Instant.parse("2026-10-10T15:00:00Z");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] OGG = "OggS\0\2\0\0\0\0\0\0opus".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PDF = "%PDF-1.7\nrelatorio".getBytes(StandardCharsets.ISO_8859_1);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path raiz;

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Analises analises = RepositoriosDeTeste.analises();
    private final ChavesGemini chaves = RepositoriosDeTeste.chaves();
    private final ModeloDeMentira modelo = new ModeloDeMentira();
    private final ConfiguracaoDaAnalise configuracao = new ConfiguracaoDaAnalise("gemini-3.8-flash", "gemini-3.8-flash", 5,
            1024, 32768, null, null);
    private CofreAesGcm cofre;
    private UUID usuario;
    private UUID atendimento;

    @BeforeEach
    void preparar() {
        cofre = new CofreAesGcm(new ChaveMestraProperties(raiz.resolve("chave-mestra")));
        usuario = PostgresDeTeste.novoUsuario(true);
        atendimento = novoAtendimento(usuario);
    }

    private AnalisarArquivo analisar() {
        return new AnalisarArquivo(analises, chaves, cofre, modelo, new PastaEmDisco(raiz), configuracao, JSON,
                Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")), Duration.ofSeconds(5), Duration.ofMillis(1), tempo -> { });
    }

    private UUID novoAtendimento(UUID dono) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em)
                        VALUES (?, ?, ?, '{}'::jsonb, 1, now(), now())
                        """).params(id, dono, "c" + id.toString().substring(0, 8)).update();
        return id;
    }

    /** Um arquivo pronto, com o conteúdo na pasta do atendimento. */
    private UUID arquivo(UUID doAtendimento, String origem, int ordem, String categoria, String tipo, String nome, byte[] conteudo)
            throws IOException {
        Path pasta = Files.createDirectories(raiz.resolve(doAtendimento.toString()).resolve("arquivos"));
        Path local = Files.write(pasta.resolve(nome), conteudo);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.arquivo (id, atendimento_id, origem, ordem, nome_original, categoria, tipo_midia,
                               tamanho, sha256, caminho, situacao)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pronto')
                        """).params(id, doAtendimento, origem, ordem, nome, categoria, tipo, (long) conteudo.length,
                Sha256.de(local), doAtendimento + "/arquivos/" + nome).update();
        return id;
    }

    private UUID imagem(String nome, byte[] conteudo) throws IOException {
        return arquivo(atendimento, "print_extra", ordem++, "imagem", "image/png", nome, conteudo);
    }

    private int ordem = 1;

    private void cadastrarChave(UUID dono) {
        chaves.salvar(new ChaveGemini(dono, cofre.cifrar(CHAVE, dono), "wxyz", true, SituacaoDaChave.NAO_TESTADA, AGORA, AGORA));
    }

    private static Tarefa tarefa(UUID atendimento, UUID dono, UUID arquivo) {
        return new Tarefa(1, TipoDeTarefa.ANALISAR, atendimento, dono, arquivo, null, 1, 5);
    }

    private ResultadoDaTarefa executar(UUID arquivo) {
        return analisar().executar(tarefa(atendimento, usuario, arquivo));
    }

    private JsonNode resultado(UUID arquivo) throws IOException {
        AnaliseGuardada guardada = analises.buscar(arquivo, 1, "gemini-3.8-flash").orElseThrow();
        return JSON.readTree(guardada.resultado());
    }

    // --------------------------------------------------------------------------------------------- testes

    @Test
    void imagemPequenaVaiNoPedidoComAChaveDoDonoEAAnaliseFicaMascarada() throws IOException {
        cadastrarChave(usuario);
        UUID print = imagem("print-loja-do-joao.png", PNG);

        ResultadoDaTarefa resultado = executar(print);

        assertThat(resultado).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        ModeloDeMentira.Geracao geracao = modelo.geracoes().get(0);
        assertThat(geracao.chave()).isEqualTo(CHAVE);
        assertThat(geracao.pedido().midia().dados()).containsExactly(PNG);
        assertThat(geracao.pedido().altaResolucao()).isTrue();
        assertThat(geracao.pedido().instrucaoDoSistema()).isEqualTo(PromptsDeAnalise.SISTEMA).contains("[omitido]");
        assertThat(geracao.pedido().instrucao() + geracao.pedido().instrucaoDoSistema()).doesNotContain("joao");
        assertThat(modelo.enviados()).isEmpty();
        JsonNode analise = resultado(print);
        assertThat(analise.at("/tela/codigo").asText()).isEqualTo("VEN010");
        assertThat(analise.at("/campos/0/valor").asText()).as("número de nota fica").isEqualTo("000123456");
        assertThat(analise.at("/campos/1/valor").asText()).as("ID de acesso remoto sai").isEqualTo("[omitido]");
        AnaliseGuardada guardada = analises.buscar(print, 1, "gemini-3.8-flash").orElseThrow();
        assertThat(guardada.tokensEntrada()).isEqualTo(1000);
        assertThat(guardada.tokensSaida()).isEqualTo(200);
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.VALIDA);
    }

    @Test
    void audioVaiPelaFilesApiEsperaFicarAtivoEEApagadoDoGeminiNoFim() throws IOException {
        cadastrarChave(usuario);
        modelo.estados(ArquivoRemoto.Estado.PROCESSANDO, ArquivoRemoto.Estado.PROCESSANDO, ArquivoRemoto.Estado.ATIVO);
        UUID audio = arquivo(atendimento, "ligacao", 1, "audio", "audio/ogg", "ligacao do cliente.ogg", OGG);

        ResultadoDaTarefa resultado = executar(audio);

        assertThat(resultado).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(modelo.enviados()).hasSize(1);
        assertThat(modelo.nomesParaExibir().get(0)).startsWith("atendimento-arquivo-").doesNotContain("cliente");
        assertThat(modelo.consultados()).hasSize(2);
        assertThat(modelo.geracoes().get(0).pedido().midia().uri()).isEqualTo("https://gemini/" + modelo.enviados().get(0));
        assertThat(modelo.geracoes().get(0).pedido().altaResolucao()).isFalse();
        assertThat(modelo.apagados()).containsExactly(modelo.enviados().get(0));
        assertThat(jdbc.sql("SELECT gemini_arquivo FROM atendimento.arquivo WHERE id = ?").param(audio).query(String.class)
                .optional()).isEmpty();
        assertThat(resultado(audio).at("/trechos/1/texto").asText()).isEqualTo("meu anydesk é [omitido]");
    }

    @Test
    void documentoComCampoDeSenhaGuardaSemOValor() throws IOException {
        cadastrarChave(usuario);
        UUID documento = arquivo(atendimento, "anexo_conversa", 1, "documento", "application/pdf", "relatorio.pdf", PDF);

        executar(documento);

        assertThat(resultado(documento).at("/dados/0/valor").asText()).isEqualTo("[omitido]");
        assertThat(resultado(documento).at("/conteudo").asText()).isEqualTo("Total: 1.234,56");
    }

    @Test
    void naoAnalisaDeNovoOQueJaTemAnaliseNemOMesmoConteudoEmOutroArquivo() throws IOException {
        cadastrarChave(usuario);
        UUID primeiro = imagem("a.png", PNG);
        UUID mesmoConteudo = imagem("b.png", PNG);
        executar(primeiro);

        assertThat(executar(primeiro)).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(executar(mesmoConteudo)).isInstanceOf(ResultadoDaTarefa.Concluida.class);

        assertThat(modelo.geracoes()).hasSize(1);
        AnaliseGuardada copia = analises.buscar(mesmoConteudo, 1, "gemini-3.8-flash").orElseThrow();
        assertThat(copia.tokensEntrada()).as("a cópia não gasta token").isNull();
        assertThat(resultado(mesmoConteudo)).isEqualTo(resultado(primeiro));
    }

    @Test
    void mesmoConteudoDeOutraPessoaEAnalisadoDeNovo() throws IOException {
        cadastrarChave(usuario);
        executar(imagem("a.png", PNG));
        UUID outra = PostgresDeTeste.novoUsuario(true);
        cadastrarChave(outra);
        UUID atendimentoDaOutra = novoAtendimento(outra);
        UUID print = arquivo(atendimentoDaOutra, "print_extra", 1, "imagem", "image/png", "a.png", PNG);

        analisar().executar(tarefa(atendimentoDaOutra, outra, print));

        assertThat(modelo.geracoes()).hasSize(2);
    }

    @Test
    void semChaveCadastradaFalhaComOCaminhoParaCadastrar() throws IOException {
        UUID print = imagem("a.png", PNG);

        ResultadoDaTarefa resultado = executar(print);

        assertThat(resultado).isEqualTo(ResultadoDaTarefa.definitiva("SEM_CHAVE_GEMINI", AnalisarArquivo.SEM_CHAVE));
        assertThat(modelo.geracoes()).isEmpty();
    }

    @Test
    void respostaForaDoEsquemaTemUmaNovaTentativaNaHoraEDepoisFalha() throws IOException {
        cadastrarChave(usuario);
        UUID primeiro = imagem("a.png", PNG);
        modelo.responder("{\"descricao\":\"sem os outros campos\"}");

        assertThat(executar(primeiro)).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(modelo.geracoes()).hasSize(2);
        AnaliseGuardada guardada = analises.buscar(primeiro, 1, "gemini-3.8-flash").orElseThrow();
        assertThat(guardada.tokensEntrada()).as("as duas chamadas").isEqualTo(2000);

        UUID segundo = imagem("b.png", new byte[]{1, 2, 3});
        modelo.responder("não é json").responder("{\"campos\":\"não é lista\"}");
        ResultadoDaTarefa falha = executar(segundo);

        assertThat(falha).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaDefinitiva.class,
                f -> assertThat(f.codigo()).isEqualTo("GEMINI_RESPOSTA_INVALIDA"));
        assertThat(analises.buscar(segundo, 1, "gemini-3.8-flash")).isEmpty();
    }

    @Test
    void chaveRecusadaPausaEMarcaAChave() throws IOException {
        cadastrarChave(usuario);
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.CHAVE_RECUSADA, "Sua chave do Gemini foi recusada."));

        ResultadoDaTarefa resultado = executar(imagem("a.png", PNG));

        assertThat(resultado).isInstanceOfSatisfying(ResultadoDaTarefa.Pausar.class, p -> {
            assertThat(p.codigo()).isEqualTo("CHAVE_GEMINI_RECUSADA");
            assertThat(p.mensagem()).contains("Minha chave do Gemini").contains("Retomar");
        });
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.RECUSADA);
    }

    @Test
    void cotaDiariaPausaELimitePorMinutoEsperaOTempoPedido() throws IOException {
        cadastrarChave(usuario);
        UUID print = imagem("a.png", PNG);
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.COTA_DIARIA, "acabou"));
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.LIMITE, "limite", Duration.ofSeconds(37), 429));

        assertThat(executar(print)).isEqualTo(ResultadoDaTarefa.pausar("GEMINI_COTA_DIARIA", AnalisarArquivo.COTA_DIARIA));
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.SEM_COTA);
        assertThat(executar(print)).isEqualTo(ResultadoDaTarefa.temporaria("GEMINI_LIMITE", "Aguardando o limite do Gemini.",
                Duration.ofSeconds(37)));
    }

    @Test
    void bloqueioIndisponibilidadeEModeloInexistente() throws IOException {
        cadastrarChave(usuario);
        UUID print = imagem("a.png", PNG);
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.BLOQUEOU, "segurança"))
                .falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.INDISPONIVEL, "503"))
                .falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.MODELO_INEXISTENTE, "404"));

        assertThat(executar(print)).isEqualTo(ResultadoDaTarefa.definitiva("GEMINI_BLOQUEOU",
                "O Gemini recusou analisar este arquivo (motivo: segurança)."));
        assertThat(executar(print)).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaTemporaria.class,
                t -> assertThat(t.codigo()).isEqualTo("GEMINI_INDISPONIVEL"));
        assertThat(executar(print)).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaDefinitiva.class,
                f -> assertThat(f.mensagem()).contains("gemini-3.8-flash").contains("atendimento.gemini.modelos"));
    }

    @Test
    void falhaPassageiraGuardaOArquivoEnviadoEAProximaTentativaNaoEnviaDeNovo() throws IOException {
        cadastrarChave(usuario);
        UUID audio = arquivo(atendimento, "ligacao", 1, "audio", "audio/ogg", "l.ogg", OGG);
        modelo.falhar(new FalhaDoModelo(FalhaDoModelo.Tipo.INDISPONIVEL, "503"));

        assertThat(executar(audio)).isInstanceOf(ResultadoDaTarefa.FalhaTemporaria.class);
        assertThat(modelo.apagados()).isEmpty();
        String guardado = jdbc.sql("SELECT gemini_arquivo FROM atendimento.arquivo WHERE id = ?").param(audio)
                .query(String.class).single();
        assertThat(guardado).isEqualTo(modelo.enviados().get(0));

        assertThat(executar(audio)).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(modelo.enviados()).as("aproveitou o já enviado").hasSize(1);
        assertThat(modelo.apagados()).containsExactly(guardado);
    }

    @Test
    void arquivoQueOGeminiNaoConseguiuProcessarFalhaEEApagado() throws IOException {
        cadastrarChave(usuario);
        modelo.estados(ArquivoRemoto.Estado.PROCESSANDO, ArquivoRemoto.Estado.FALHOU);
        UUID audio = arquivo(atendimento, "ligacao", 1, "audio", "audio/ogg", "l.ogg", OGG);

        ResultadoDaTarefa resultado = executar(audio);

        assertThat(resultado).isInstanceOfSatisfying(ResultadoDaTarefa.FalhaDefinitiva.class,
                f -> assertThat(f.codigo()).isEqualTo("GEMINI_ARQUIVO_FALHOU"));
        assertThat(modelo.geracoes()).isEmpty();
        assertThat(modelo.apagados()).hasSize(1);
    }

    @Test
    void imagemGrandeTambemVaiPelaFilesApi() throws IOException {
        cadastrarChave(usuario);
        byte[] grande = new byte[2048];
        System.arraycopy(PNG, 0, grande, 0, PNG.length);

        executar(imagem("grande.png", grande));

        assertThat(modelo.enviados()).hasSize(1);
        assertThat(modelo.geracoes().get(0).pedido().altaResolucao()).isTrue();
    }

    @Test
    void arquivoQueNaoEAnalisadoOuQueSaiuSoConclui() throws IOException {
        cadastrarChave(usuario);
        UUID video = arquivo(atendimento, "video", 1, "video", "video/mp4", "v.mp4", new byte[]{1});

        assertThat(executar(video)).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(analisar().executar(tarefa(atendimento, usuario, UUID.randomUUID()))).isInstanceOf(ResultadoDaTarefa.Concluida.class);
        assertThat(modelo.geracoes()).isEmpty();
    }
}
