package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.ArquivoParaAnalisar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.CofreException;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
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
 * próprio pedido; áudio, documento e imagem grande vão antes pela Files API e são apagados de lá ao fim.
 *
 * <p>A análise guardada não é refeita: o mesmo arquivo (ou outro com o mesmo conteúdo, do mesmo usuário) na mesma
 * versão do prompt e no mesmo modelo reaproveita a que já existe. A resposta passa pela máscara antes de gravar.
 */
@Component
public class AnalisarArquivo implements ExecutorDeTarefa {

    private static final Logger log = LoggerFactory.getLogger(AnalisarArquivo.class);

    static final String SEM_CHAVE = "Cadastre sua chave do Gemini em Atendimentos → Minha chave do Gemini e clique em "
            + "Processar de novo.";
    static final String COTA_DIARIA = "A cota diária da sua chave do Gemini acabou. Ela renova por volta das 4h–5h "
            + "(meia-noite no horário do Pacífico); depois disso, clique em Retomar e o processamento continua de onde parou.";

    /** Para os testes não esperarem de verdade. */
    interface Espera {
        void esperar(Duration tempo) throws InterruptedException;
    }

    private final Analises analises;
    private final ChavesGemini chaves;
    private final Cofre cofre;
    private final ModeloDeIA modelo;
    private final PastaDosAtendimentos pasta;
    private final ConfiguracaoDaAnalise configuracao;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration esperaArquivo;
    private final Duration intervaloConsulta;
    private final Espera espera;
    private final MascaraDaAnalise mascara = new MascaraDaAnalise();

    @Autowired
    public AnalisarArquivo(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo, PastaDosAtendimentos pasta,
                           ConfiguracaoDaAnalise configuracao, ObjectMapper json, Clock clock,
                           @Value("${atendimento.gemini.espera-arquivo:5m}") Duration esperaArquivo,
                           @Value("${atendimento.gemini.intervalo-consulta:2s}") Duration intervaloConsulta) {
        this(analises, chaves, cofre, modelo, pasta, configuracao, json, clock, esperaArquivo, intervaloConsulta,
                tempo -> Thread.sleep(tempo.toMillis()));
    }

    AnalisarArquivo(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo, PastaDosAtendimentos pasta,
                    ConfiguracaoDaAnalise configuracao, ObjectMapper json, Clock clock, Duration esperaArquivo,
                    Duration intervaloConsulta, Espera espera) {
        this.analises = analises;
        this.chaves = chaves;
        this.cofre = cofre;
        this.modelo = modelo;
        this.pasta = pasta;
        this.configuracao = configuracao;
        this.json = json;
        this.clock = clock;
        this.esperaArquivo = esperaArquivo;
        this.intervaloConsulta = intervaloConsulta;
        this.espera = espera;
    }

    @Override
    public TipoDeTarefa tipo() {
        return TipoDeTarefa.ANALISAR;
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
        if (analises.buscar(arquivo.id(), prompt.versao(), nomeDoModelo).isPresent()) {
            return ResultadoDaTarefa.concluida();
        }
        Optional<AnaliseGuardada> igual = analises.doMesmoConteudo(arquivo.usuarioId(), arquivo.sha256(), prompt.versao(),
                nomeDoModelo, arquivo.id());
        if (igual.isPresent()) {
            analises.salvar(arquivo.id(), prompt.versao(), nomeDoModelo, igual.get().resultado(), null, null, null,
                    clock.instant());
            log.info("Análise do arquivo {} reaproveitada de outro com o mesmo conteúdo", arquivo.id());
            return ResultadoDaTarefa.concluida();
        }

        Optional<ChaveGemini> guardada = chaves.buscar(arquivo.usuarioId());
        if (guardada.isEmpty()) {
            return ResultadoDaTarefa.definitiva("SEM_CHAVE_GEMINI", SEM_CHAVE);
        }
        String chave;
        try {
            chave = cofre.decifrar(guardada.get().cifrada(), arquivo.usuarioId());
        } catch (CofreException e) {
            return ResultadoDaTarefa.definitiva("CHAVE_GEMINI_ILEGIVEL", "Não foi possível abrir a sua chave do Gemini "
                    + "guardada (a chave mestra do servidor mudou). Cadastre a chave de novo e clique em Processar.");
        }

        Path local = pasta.localDoArquivo(arquivo.caminho());
        ArquivoRemoto remoto = null;
        boolean apagarRemoto = true;
        long inicio = System.nanoTime();
        try {
            PedidoAoModelo.Midia midia;
            if (arquivo.categoria() == CategoriaDoArquivo.IMAGEM && tamanho(arquivo, local) <= configuracao.inlineMaximo()) {
                midia = PedidoAoModelo.Midia.noPedido(tipoDeMidia(arquivo), Files.readAllBytes(local));
            } else {
                remoto = remotoPronto(chave, arquivo, local);
                midia = PedidoAoModelo.Midia.enviada(remoto);
            }
            PedidoAoModelo pedido = new PedidoAoModelo(nomeDoModelo, PromptsDeAnalise.SISTEMA, prompt.instrucao(),
                    prompt.esquema(), midia, prompt.altaResolucao(), configuracao.temperatura(), configuracao.raciocinio(),
                    configuracao.maxTokensSaida());
            Lida lida = gerarEConferir(chave, pedido, prompt);
            String resultado = json.writeValueAsString(mascara.aplicar(lida.resultado()));
            int duracao = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - inicio) / 1_000_000);
            analises.salvar(arquivo.id(), prompt.versao(), nomeDoModelo, resultado, lida.tokensEntrada(), lida.tokensSaida(),
                    duracao, clock.instant());
            if (guardada.get().situacao() != SituacaoDaChave.VALIDA) {
                chaves.atualizarSituacao(arquivo.usuarioId(), SituacaoDaChave.VALIDA, clock.instant());
            }
            log.info("Arquivo {} ({}) analisado com {}: {} + {} tokens, {} ms", arquivo.id(), arquivo.categoria().codigo(),
                    nomeDoModelo, lida.tokensEntrada(), lida.tokensSaida(), duracao);
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
                    return comTipo(aguardarAtivo(chave, anterior), arquivo);
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
            return comTipo(aguardarAtivo(chave, enviado), arquivo);
        } catch (FalhaDoModelo falha) {
            if (falha.tipo() == FalhaDoModelo.Tipo.ARQUIVO_FALHOU) {
                apagarSemFalhar(chave, arquivo.id(), enviado.nome()); // não serve para nada: sai já
            }
            throw falha;
        }
    }

    /** Áudio e vídeo passam por um processamento no Google antes de poderem ser usados. */
    private ArquivoRemoto aguardarAtivo(String chave, ArquivoRemoto arquivo) {
        Instant limite = clock.instant().plus(esperaArquivo);
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
                log.info("Resposta do Gemini fora do formato ({}): tentando de novo", falha.getMessage());
            }
        }
    }

    /** O JSON é um objeto com os campos do esquema (as listas como listas). */
    JsonNode conferir(String texto, PromptsDeAnalise.Prompt prompt) {
        String limpo = texto.strip();
        if (limpo.startsWith("```")) {
            limpo = limpo.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        JsonNode lido;
        try {
            lido = json.readTree(limpo);
        } catch (IOException e) {
            throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "não é JSON");
        }
        if (lido == null || !lido.isObject()) {
            throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "não é um objeto JSON");
        }
        for (String campo : prompt.obrigatorios()) {
            if (!lido.has(campo) || lido.get(campo).isNull()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "faltou o campo " + campo);
            }
        }
        for (String lista : prompt.listas()) {
            if (!lido.get(lista).isArray()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "o campo " + lista + " não é uma lista");
            }
        }
        return lido;
    }

    /** O que fazer com cada falha (tabela 6.4 da arquitetura). */
    private ResultadoDaTarefa traduzir(FalhaDoModelo falha, ArquivoParaAnalisar arquivo, String nomeDoModelo) {
        UUID usuario = arquivo.usuarioId();
        Instant agora = clock.instant();
        return switch (falha.tipo()) {
            case CHAVE_RECUSADA -> {
                chaves.atualizarSituacao(usuario, SituacaoDaChave.RECUSADA, agora);
                yield ResultadoDaTarefa.pausar("CHAVE_GEMINI_RECUSADA", falha.getMessage() + " Confira ou cadastre de novo "
                        + "em Atendimentos → Minha chave do Gemini e clique em Retomar.");
            }
            case LIMITE -> ResultadoDaTarefa.temporaria("GEMINI_LIMITE", "Aguardando o limite do Gemini.", falha.esperar());
            case COTA_DIARIA -> {
                chaves.atualizarSituacao(usuario, SituacaoDaChave.SEM_COTA, agora);
                yield ResultadoDaTarefa.pausar("GEMINI_COTA_DIARIA", COTA_DIARIA);
            }
            case SEM_CREDITO -> ResultadoDaTarefa.pausar("GEMINI_SEM_CREDITO", "Os créditos da sua chave do Gemini acabaram. "
                    + "Compre mais créditos no Google AI Studio e clique em Retomar.");
            case INDISPONIVEL -> ResultadoDaTarefa.temporaria("GEMINI_INDISPONIVEL", "Gemini indisponível; tentando de novo.");
            case SEM_INTERNET -> ResultadoDaTarefa.temporaria("GEMINI_INDISPONIVEL", "Sem conexão com o Google; tentando de novo.");
            case BLOQUEOU -> ResultadoDaTarefa.definitiva("GEMINI_BLOQUEOU", "O Gemini recusou analisar este arquivo (motivo: "
                    + falha.getMessage() + ").");
            case RESPOSTA_INVALIDA -> ResultadoDaTarefa.definitiva("GEMINI_RESPOSTA_INVALIDA", "Resposta inválida do Gemini "
                    + "(duas vezes seguidas). Clique em Tentar de novo mais tarde.");
            case MODELO_INEXISTENTE -> ResultadoDaTarefa.definitiva("GEMINI_MODELO", "O modelo " + nomeDoModelo + " não "
                    + "existe ou não está liberado para a sua chave. Avise o administrador (configuração "
                    + "atendimento.gemini.modelos).");
            case PEDIDO_RECUSADO -> ResultadoDaTarefa.definitiva("GEMINI_PEDIDO_RECUSADO", falha.getMessage());
            case ARQUIVO_FALHOU -> {
                analises.esquecerArquivoRemoto(arquivo.id());
                yield ResultadoDaTarefa.definitiva("GEMINI_ARQUIVO_FALHOU", "O Gemini não conseguiu processar este arquivo. "
                        + "Confira se ele abre no computador; se abrir, tente de novo ou envie em outro formato.");
            }
        };
    }

    // ---------------------------------------------------------------------------------------------- apoio

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
