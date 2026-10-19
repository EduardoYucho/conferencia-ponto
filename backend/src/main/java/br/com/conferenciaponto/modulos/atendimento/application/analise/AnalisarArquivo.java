package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A tarefa "analisar": uma chamada ao Gemini por arquivo, com a chave do dono do atendimento. Imagem pequena vai no
 * próprio pedido; áudio, documento, vídeo e imagem grande vão antes pela Files API e são apagados de lá ao fim. No
 * vídeo, o Gemini olha a taxa de quadros da configuração e devolve os passos; logo depois, o ffmpeg tira o quadro de
 * cada passo (sem o ffmpeg, a análise vale do mesmo jeito e os passos ficam sem quadro).
 *
 * <p>A análise guardada não é refeita: o mesmo arquivo (ou outro com o mesmo conteúdo, do mesmo usuário) na mesma
 * versão do prompt e no mesmo modelo reaproveita a que já existe. A resposta passa pela máscara antes de gravar.
 */
@Component
public class AnalisarArquivo implements ExecutorDeTarefa {

    private static final Logger log = LoggerFactory.getLogger(AnalisarArquivo.class);

    static final String SEM_CHAVE = ChamadasAoGemini.SEM_CHAVE;
    static final String COTA_DIARIA = ChamadasAoGemini.COTA_DIARIA;

    /** Para os testes não esperarem de verdade. */
    interface Espera {
        void esperar(Duration tempo) throws InterruptedException;
    }

    private final Analises analises;
    private final ChamadasAoGemini gemini;
    private final ModeloDeIA modelo;
    private final PastaDosAtendimentos pasta;
    private final ConfiguracaoDaAnalise configuracao;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration esperaArquivo;
    private final Duration intervaloConsulta;
    private final Espera espera;
    private final QuadrosDoVideo quadros;
    private final MascaraDaAnalise mascara = new MascaraDaAnalise();

    @Autowired
    public AnalisarArquivo(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo, PastaDosAtendimentos pasta,
                           ConfiguracaoDaAnalise configuracao, ObjectMapper json, Clock clock,
                           @Value("${atendimento.gemini.espera-arquivo:5m}") Duration esperaArquivo,
                           @Value("${atendimento.gemini.intervalo-consulta:2s}") Duration intervaloConsulta,
                           QuadrosDoVideo quadros) {
        this(analises, chaves, cofre, modelo, pasta, configuracao, json, clock, esperaArquivo, intervaloConsulta,
                tempo -> Thread.sleep(tempo.toMillis()), quadros);
    }

    AnalisarArquivo(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo, PastaDosAtendimentos pasta,
                    ConfiguracaoDaAnalise configuracao, ObjectMapper json, Clock clock, Duration esperaArquivo,
                    Duration intervaloConsulta, Espera espera, QuadrosDoVideo quadros) {
        this.analises = analises;
        this.gemini = new ChamadasAoGemini(chaves, cofre, json, clock, modelo.local());
        this.modelo = modelo;
        this.pasta = pasta;
        this.configuracao = configuracao;
        this.json = json;
        this.clock = clock;
        this.esperaArquivo = esperaArquivo;
        this.intervaloConsulta = intervaloConsulta;
        this.espera = espera;
        this.quadros = quadros;
    }

    @Override
    public TipoDeTarefa tipo() {
        return TipoDeTarefa.ANALISAR;
    }

    /** A IA local usa a placa de vídeo deste computador: um de cada vez (vários juntos dividiriam a memória dela). */
    @Override
    public int limiteSimultaneo() {
        return modelo.local() ? 1 : 0;
    }

    @Override
    public ResultadoDaTarefa executar(Tarefa tarefa) {
        Optional<ArquivoParaAnalisar> talvez = analises.arquivo(tarefa.arquivoId());
        if (talvez.isEmpty() || !FilaDeAnalises.analisavel(talvez.get())) {
            return ResultadoDaTarefa.concluida(); // o arquivo saiu, foi trocado ou ainda não tem conteúdo
        }
        ArquivoParaAnalisar arquivo = talvez.get();
        PromptsDeAnalise.Prompt prompt = PromptsDeAnalise.para(arquivo.categoria()).orElseThrow();
        String nomeDoModelo = configuracao.modelo(arquivo.categoria());
        String identificacao = configuracao.identificacao(arquivo.categoria());
        Optional<AnaliseGuardada> feita = analises.buscar(arquivo.id(), prompt.versao(), identificacao);
        if (feita.isPresent()) {
            prepararQuadros(arquivo, feita.get().resultado());
            return ResultadoDaTarefa.concluida();
        }
        Optional<AnaliseGuardada> igual = analises.doMesmoConteudo(arquivo.usuarioId(), arquivo.sha256(), prompt.versao(),
                identificacao, arquivo.id());
        if (igual.isPresent()) {
            analises.salvar(arquivo.id(), prompt.versao(), identificacao, igual.get().resultado(), null, null, null,
                    clock.instant());
            log.info("Análise do arquivo {} reaproveitada de outro com o mesmo conteúdo", arquivo.id());
            prepararQuadros(arquivo, igual.get().resultado());
            return ResultadoDaTarefa.concluida();
        }
        boolean video = arquivo.categoria() == CategoriaDoArquivo.VIDEO;
        if (video && !modelo.local() && arquivo.tamanho() != null && arquivo.tamanho() > configuracao.videoMaximo()) {
            return ResultadoDaTarefa.definitiva("VIDEO_GRANDE_DEMAIS", "O vídeo tem " + tamanhoLegivel(arquivo.tamanho())
                    + "; o Gemini aceita até " + tamanhoLegivel(configuracao.videoMaximo()) + ". Corte o vídeo (só o trecho "
                    + "do problema) ou grave de novo com uma resolução menor e envie.");
        }

        ChamadasAoGemini.ChaveDoDono dono = gemini.chaveDo(arquivo.usuarioId());
        if (!dono.aberta()) {
            return dono.falha();
        }
        String chave = dono.chave();

        Path local = pasta.localDoArquivo(arquivo.caminho());
        ArquivoRemoto remoto = null;
        boolean apagarRemoto = true;
        long inicio = System.nanoTime();
        try {
            PedidoAoModelo.Midia midia;
            if (modelo.local()) {
                midia = PedidoAoModelo.Midia.doDisco(tipoDeMidia(arquivo), local); // a IA local lê do disco
            } else if (arquivo.categoria() == CategoriaDoArquivo.IMAGEM && tamanho(arquivo, local) <= configuracao.inlineMaximo()) {
                midia = PedidoAoModelo.Midia.noPedido(tipoDeMidia(arquivo), Files.readAllBytes(local));
            } else {
                remoto = remotoPronto(chave, arquivo, local);
                midia = video ? PedidoAoModelo.Midia.video(remoto, configuracao.quadrosPorSegundo())
                        : PedidoAoModelo.Midia.enviada(remoto);
            }
            PedidoAoModelo pedido = new PedidoAoModelo(nomeDoModelo, PromptsDeAnalise.SISTEMA, prompt.instrucao(),
                    prompt.esquema(), midia, prompt.altaResolucao(), configuracao.temperatura(), configuracao.raciocinio(),
                    configuracao.maxTokensSaida());
            Lida lida = gerarEConferir(chave, pedido, prompt);
            String resultado = json.writeValueAsString(mascara.aplicar(lida.resultado()));
            int duracao = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - inicio) / 1_000_000);
            analises.salvar(arquivo.id(), prompt.versao(), identificacao, resultado, lida.tokensEntrada(), lida.tokensSaida(),
                    duracao, clock.instant());
            gemini.usoDeuCerto(arquivo.usuarioId(), dono);
            log.info("Arquivo {} ({}) analisado com {}: {} + {} tokens, {} ms", arquivo.id(), arquivo.categoria().codigo(),
                    identificacao, lida.tokensEntrada(), lida.tokensSaida(), duracao);
            prepararQuadros(arquivo, resultado);
            return ResultadoDaTarefa.concluida();
        } catch (FalhaDoModelo falha) {
            ResultadoDaTarefa resultado = traduzir(falha, arquivo, nomeDoModelo);
            // passageira ou pausa: o arquivo enviado fica para a próxima tentativa (o Google apaga em 48 h)
            apagarRemoto = resultado instanceof ResultadoDaTarefa.FalhaDefinitiva;
            return resultado;
        } catch (IOException e) {
            log.warn("Arquivo {}: não foi possível ler para analisar ({})", arquivo.id(), e.getClass().getSimpleName());
            return ResultadoDaTarefa.definitiva("ARQUIVO_ILEGIVEL", "Não foi possível ler o arquivo no servidor. "
                    + "Tire o arquivo e envie de novo.");
        } finally {
            if (remoto != null && apagarRemoto) {
                apagarSemFalhar(chave, arquivo.id(), remoto.nome());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------- etapas

    /** O arquivo enviado e pronto no Gemini: aproveita o que já foi enviado antes (uma queda no meio), se ainda vale. */
    private ArquivoRemoto remotoPronto(String chave, ArquivoParaAnalisar arquivo, Path local) throws IOException {
        Instant agora = clock.instant();
        if (arquivo.arquivoRemoto() != null && arquivo.arquivoRemotoExpiraEm() != null
                && arquivo.arquivoRemotoExpiraEm().isAfter(agora.plus(Duration.ofHours(1)))) {
            try {
                ArquivoRemoto anterior = modelo.consultar(chave, arquivo.arquivoRemoto());
                if (anterior.estado() != ArquivoRemoto.Estado.FALHOU) {
                    return comTipo(aguardarAtivo(chave, anterior, arquivo.categoria() == CategoriaDoArquivo.VIDEO), arquivo);
                }
            } catch (FalhaDoModelo falha) {
                if (falha.tipo() != FalhaDoModelo.Tipo.ARQUIVO_FALHOU) {
                    throw falha;
                }
            }
            analises.esquecerArquivoRemoto(arquivo.id());
        }
        ArquivoRemoto enviado = modelo.enviar(chave, local, tipoDeMidia(arquivo), tamanho(arquivo, local),
                "atendimento-arquivo-" + arquivo.id().toString().substring(0, 8));
        analises.guardarArquivoRemoto(arquivo.id(), enviado.nome(),
                enviado.expiraEm() != null ? enviado.expiraEm() : agora.plus(Duration.ofHours(47)));
        try {
            return comTipo(aguardarAtivo(chave, enviado, arquivo.categoria() == CategoriaDoArquivo.VIDEO), arquivo);
        } catch (FalhaDoModelo falha) {
            if (falha.tipo() == FalhaDoModelo.Tipo.ARQUIVO_FALHOU) {
                apagarSemFalhar(chave, arquivo.id(), enviado.nome()); // não serve para nada: sai já
            }
            throw falha;
        }
    }

    /** Áudio e vídeo passam por um processamento no Google antes de poderem ser usados (o vídeo demora mais). */
    private ArquivoRemoto aguardarAtivo(String chave, ArquivoRemoto arquivo, boolean video) {
        Instant limite = clock.instant().plus(video ? configuracao.esperaVideo() : esperaArquivo);
        ArquivoRemoto atual = arquivo;
        while (!atual.ativo()) {
            if (atual.estado() == ArquivoRemoto.Estado.FALHOU) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.ARQUIVO_FALHOU, "O Gemini não conseguiu processar o arquivo.");
            }
            if (clock.instant().isAfter(limite)) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.INDISPONIVEL, "O Gemini demorou demais para preparar o arquivo.");
            }
            try {
                espera.esperar(intervaloConsulta);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.INDISPONIVEL, "A análise foi interrompida.");
            }
            atual = modelo.consultar(chave, atual.nome());
        }
        return atual;
    }

    private record Lida(JsonNode resultado, int tokensEntrada, int tokensSaida) {
    }

    /** Resposta fora do esquema: uma nova tentativa na hora; na segunda, falha do item. */
    private Lida gerarEConferir(String chave, PedidoAoModelo pedido, PromptsDeAnalise.Prompt prompt) {
        int entrada = 0;
        int saida = 0;
        for (int vez = 1; ; vez++) {
            try {
                RespostaDoModelo resposta = modelo.gerar(chave, pedido);
                entrada += resposta.tokensEntrada();
                saida += resposta.tokensSaida();
                return new Lida(conferir(resposta.texto(), prompt), entrada, saida);
            } catch (FalhaDoModelo falha) {
                if (falha.tipo() != FalhaDoModelo.Tipo.RESPOSTA_INVALIDA || vez >= 2) {
                    throw falha;
                }
                log.info("Resposta do modelo fora do formato ({}): tentando de novo", falha.getMessage());
            }
        }
    }

    /** O JSON é um objeto com os campos do esquema (as listas como listas). */
    JsonNode conferir(String texto, PromptsDeAnalise.Prompt prompt) {
        return gemini.lerObjeto(texto, prompt.obrigatorios(), prompt.listas());
    }

    /** O que fazer com cada falha (tabela 6.4 da arquitetura); o arquivo que o Google não processou sai de lá. */
    private ResultadoDaTarefa traduzir(FalhaDoModelo falha, ArquivoParaAnalisar arquivo, String nomeDoModelo) {
        if (falha.tipo() == FalhaDoModelo.Tipo.ARQUIVO_FALHOU) {
            analises.esquecerArquivoRemoto(arquivo.id());
        }
        return gemini.traduzir(falha, arquivo.usuarioId(), nomeDoModelo, "analisar " + qual(arquivo));
    }

    /** "o vídeo gravacao.mp4", "a imagem print.png" (para as mensagens da tela). */
    static String qual(ArquivoParaAnalisar arquivo) {
        String tipo = arquivo.categoria() == null ? "o arquivo" : switch (arquivo.categoria()) {
            case IMAGEM -> "a imagem";
            case AUDIO -> "o áudio";
            case VIDEO -> "o vídeo";
            case DOCUMENTO -> "o documento";
            default -> "o arquivo";
        };
        return arquivo.nome() == null || arquivo.nome().isBlank() ? tipo : tipo + " " + arquivo.nome();
    }

    // ---------------------------------------------------------------------------------------------- apoio

    /** No vídeo: tira o quadro de cada passo que ainda não tem (não falha a análise; o que faltar fica no aviso). */
    private void prepararQuadros(ArquivoParaAnalisar arquivo, String resultado) {
        if (arquivo.categoria() != CategoriaDoArquivo.VIDEO || quadros == null) {
            return;
        }
        try {
            QuadrosDoVideo.Preparo preparo = quadros.preparar(arquivo.atendimentoId(), arquivo.id(), arquivo.caminho(),
                    json.readTree(resultado));
            if (preparo.semFfmpeg()) {
                log.info("Vídeo {} analisado sem quadros: o ffmpeg não está instalado", arquivo.id());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Vídeo {}: os quadros não foram tirados ({})", arquivo.id(), e.getClass().getSimpleName());
        }
    }

    private static String tamanhoLegivel(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) {
            return String.format(java.util.Locale.ROOT, "%.1f GB", gb).replace('.', ',');
        }
        return (bytes / (1024 * 1024)) + " MB";
    }

    private void apagarSemFalhar(String chave, UUID arquivoId, String nome) {
        try {
            modelo.apagar(chave, nome);
            analises.esquecerArquivoRemoto(arquivoId);
        } catch (RuntimeException e) {
            log.warn("Não foi possível apagar do Gemini o arquivo enviado ({}): ele expira em 48 h", e.getClass().getSimpleName());
        }
    }

    private static ArquivoRemoto comTipo(ArquivoRemoto remoto, ArquivoParaAnalisar arquivo) {
        return remoto.tipoDeMidia() != null ? remoto
                : new ArquivoRemoto(remoto.nome(), remoto.uri(), tipoDeMidia(arquivo), remoto.estado(), remoto.expiraEm());
    }

    private static String tipoDeMidia(ArquivoParaAnalisar arquivo) {
        return arquivo.tipoDeMidia() != null ? arquivo.tipoDeMidia() : "application/octet-stream";
    }

    private static long tamanho(ArquivoParaAnalisar arquivo, Path local) throws IOException {
        return arquivo.tamanho() != null ? arquivo.tamanho() : Files.size(local);
    }
}
