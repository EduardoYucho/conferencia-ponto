package br.com.conferenciaponto.modulos.atendimento.application.ia;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.CofreException;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * O que as tarefas que usam o modelo têm em comum: a chave do dono do atendimento, a leitura do JSON da resposta e
 * o que fazer com cada falha (tabela 6.4 da arquitetura). Com a IA local (o modelo roda neste computador), não há
 * chave nem cota: a "chave" é só um marcador, e as falhas são as da IA local (o Ollama parado, a placa de vídeo
 * ocupada por um jogo).
 */
@Component
public class ChamadasAoGemini {

    public static final String SEM_CHAVE = "Cadastre sua chave do Gemini em Atendimentos → Minha chave do Gemini e "
            + "clique em Processar de novo.";
    public static final String COTA_DIARIA = "A cota diária da sua chave do Gemini acabou. Ela renova por volta das "
            + "4h–5h (meia-noite no horário do Pacífico); depois disso, clique em Retomar e o processamento continua de "
            + "onde parou.";

    /** O que vai no lugar da chave com a IA local (o modelo não usa chave). */
    public static final String CHAVE_LOCAL = "ia-local";
    /** Quanto esperar para conferir de novo se a placa de vídeo ficou livre, quando a IA local não disse. */
    static final Duration ESPERA_DA_PLACA = Duration.ofMinutes(2);

    private final ChavesGemini chaves;
    private final Cofre cofre;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean local;

    @Autowired
    public ChamadasAoGemini(ChavesGemini chaves, Cofre cofre, ObjectMapper json, Clock clock, ModeloDeIA modelo) {
        this(chaves, cofre, json, clock, modelo.local());
    }

    /** Com o Gemini (a API, com a chave de cada pessoa). */
    public ChamadasAoGemini(ChavesGemini chaves, Cofre cofre, ObjectMapper json, Clock clock) {
        this(chaves, cofre, json, clock, false);
    }

    public ChamadasAoGemini(ChavesGemini chaves, Cofre cofre, ObjectMapper json, Clock clock, boolean local) {
        this.chaves = chaves;
        this.cofre = cofre;
        this.json = json;
        this.clock = clock;
        this.local = local;
    }

    /** O modelo roda neste computador (sem chave nem cota). */
    public boolean local() {
        return local;
    }

    /**
     * A chave aberta, ou a falha que a tarefa devolve.
     *
     * @param situacao a situação guardada da chave (para marcar como válida depois de um uso que deu certo)
     */
    public record ChaveDoDono(String chave, SituacaoDaChave situacao, ResultadoDaTarefa falha) {

        public boolean aberta() {
            return chave != null;
        }

        @Override
        public String toString() {
            return "ChaveDoDono[aberta=" + aberta() + "]";
        }
    }

    public ChaveDoDono chaveDo(UUID usuario) {
        if (local) {
            return new ChaveDoDono(CHAVE_LOCAL, SituacaoDaChave.VALIDA, null);
        }
        Optional<ChaveGemini> guardada = chaves.buscar(usuario);
        if (guardada.isEmpty()) {
            return new ChaveDoDono(null, null, ResultadoDaTarefa.definitiva("SEM_CHAVE_GEMINI", SEM_CHAVE));
        }
        try {
            return new ChaveDoDono(cofre.decifrar(guardada.get().cifrada(), usuario), guardada.get().situacao(), null);
        } catch (CofreException e) {
            return new ChaveDoDono(null, null, ResultadoDaTarefa.definitiva("CHAVE_GEMINI_ILEGIVEL", "Não foi possível "
                    + "abrir a sua chave do Gemini guardada (a chave mestra do servidor mudou). Cadastre a chave de novo "
                    + "e clique em Processar."));
        }
    }

    /** Um uso que deu certo: a chave passa a constar como válida (se estava sem cota ou não testada). */
    public void usoDeuCerto(UUID usuario, ChaveDoDono chave) {
        if (chave.situacao() != SituacaoDaChave.VALIDA) {
            chaves.atualizarSituacao(usuario, SituacaoDaChave.VALIDA, clock.instant());
        }
    }

    /**
     * O JSON da resposta: um objeto, com os campos obrigatórios (e as listas como listas). Fora disso, vem
     * {@link FalhaDoModelo} RESPOSTA_INVALIDA (quem chama tenta mais uma vez).
     */
    public JsonNode lerObjeto(String texto, List<String> obrigatorios, List<String> listas) {
        String limpo = texto == null ? "" : texto.strip();
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
        for (String campo : obrigatorios) {
            if (!lido.has(campo) || lido.get(campo).isNull()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "faltou o campo " + campo);
            }
        }
        for (String lista : listas) {
            if (!lido.get(lista).isArray()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "o campo " + lista + " não é uma lista");
            }
        }
        return lido;
    }

    /** "analisar este arquivo" → "analisar este arquivo" (o que ficou por fazer, para a mensagem da pausa). */
    private static String semIsso(String oQue) {
        return oQue == null || oQue.isBlank() ? "terminar esta etapa" : oQue;
    }

    /**
     * O resultado da tarefa para cada falha do Gemini. A chave recusada e a cota do dia pausam as tarefas de IA do
     * atendimento (os downloads seguem) até o Retomar.
     *
     * @param oQue o que a tarefa fazia, para a mensagem de bloqueio (ex.: "analisar este arquivo")
     */
    public ResultadoDaTarefa traduzir(FalhaDoModelo falha, UUID usuario, String nomeDoModelo, String oQue) {
        if (local) {
            return traduzirLocal(falha, nomeDoModelo, oQue);
        }
        Instant agora = clock.instant();
        return switch (falha.tipo()) {
            case CHAVE_RECUSADA -> {
                chaves.atualizarSituacao(usuario, SituacaoDaChave.RECUSADA, agora);
                yield ResultadoDaTarefa.pausar("CHAVE_GEMINI_RECUSADA", falha.getMessage() + " Confira ou cadastre de novo "
                        + "em Atendimentos → Minha chave do Gemini e clique em Retomar.");
            }
            case LIMITE -> ResultadoDaTarefa.temporariaQuePausa("GEMINI_LIMITE", "Aguardando o limite do Gemini.",
                    falha.esperar(), "O limite por minuto do Gemini não liberou depois de várias tentativas e o "
                            + "processamento parou aqui, sem " + semIsso(oQue) + ". Clique em Retomar daqui a alguns minutos.");
            case COTA_DIARIA -> {
                chaves.atualizarSituacao(usuario, SituacaoDaChave.SEM_COTA, agora);
                yield ResultadoDaTarefa.pausar("GEMINI_COTA_DIARIA", COTA_DIARIA);
            }
            case SEM_CREDITO -> ResultadoDaTarefa.pausar("GEMINI_SEM_CREDITO", "Os créditos da sua chave do Gemini acabaram. "
                    + "Compre mais créditos no Google AI Studio e clique em Retomar.");
            case INDISPONIVEL -> ResultadoDaTarefa.temporariaQuePausa("GEMINI_INDISPONIVEL",
                    "Gemini indisponível (sobrecarregado); tentando de novo.", null, "O Gemini ficou indisponível "
                            + "(sobrecarregado) em todas as tentativas e o processamento parou aqui, sem " + semIsso(oQue)
                            + ". Nada é escrito sem isso. Clique em Retomar daqui a alguns minutos.");
            case SEM_INTERNET -> ResultadoDaTarefa.temporariaQuePausa("GEMINI_INDISPONIVEL",
                    "Sem conexão com o Google; tentando de novo.", null, "O servidor ficou sem conexão com o Google em "
                            + "todas as tentativas e o processamento parou aqui, sem " + semIsso(oQue) + ". Clique em "
                            + "Retomar quando a internet voltar.");
            case BLOQUEOU -> ResultadoDaTarefa.definitiva("GEMINI_BLOQUEOU", "O Gemini recusou " + oQue + " (motivo: "
                    + falha.getMessage() + ").");
            case RESPOSTA_INVALIDA -> ResultadoDaTarefa.definitiva("GEMINI_RESPOSTA_INVALIDA", "Resposta inválida do Gemini "
                    + "(duas vezes seguidas). Clique em Tentar de novo mais tarde.");
            case MODELO_INEXISTENTE -> ResultadoDaTarefa.definitiva("GEMINI_MODELO", "O modelo " + nomeDoModelo + " não "
                    + "existe ou não está liberado para a sua chave. Avise o administrador (configuração "
                    + "atendimento.gemini.modelos).");
            case PEDIDO_RECUSADO -> ResultadoDaTarefa.definitiva("GEMINI_PEDIDO_RECUSADO", falha.getMessage());
            case ARQUIVO_FALHOU -> ResultadoDaTarefa.definitiva("GEMINI_ARQUIVO_FALHOU", "O Gemini não conseguiu processar "
                    + "este arquivo. Confira se ele abre no computador; se abrir, tente de novo ou envie em outro formato.");
            case IA_LOCAL_PARADA, PLACA_OCUPADA -> ResultadoDaTarefa.temporaria("GEMINI_INDISPONIVEL", falha.getMessage());
        };
    }

    /** As falhas da IA local: nada de chave nem de cota; a placa ocupada espera, sem gastar tentativa. */
    private ResultadoDaTarefa traduzirLocal(FalhaDoModelo falha, String nomeDoModelo, String oQue) {
        return switch (falha.tipo()) {
            case PLACA_OCUPADA -> ResultadoDaTarefa.aguardar("PLACA_OCUPADA", "Aguardando a placa de vídeo ficar livre "
                    + "(um jogo ou outro programa está usando a memória dela); continua sozinho.",
                    falha.esperar() != null ? falha.esperar() : ESPERA_DA_PLACA);
            case IA_LOCAL_PARADA -> ResultadoDaTarefa.temporariaQuePausa("IA_LOCAL_PARADA",
                    "A IA local (Ollama) não respondeu; tentando de novo.", null, "A IA local (o Ollama, neste "
                            + "computador) não respondeu em nenhuma tentativa e o processamento parou aqui, sem "
                            + semIsso(oQue) + ". Confira se o Ollama está aberto (o ícone da lhama perto do relógio do "
                            + "Windows) e clique em Retomar.");
            case INDISPONIVEL, SEM_INTERNET, LIMITE -> ResultadoDaTarefa.temporariaQuePausa("IA_LOCAL_INDISPONIVEL",
                    "A IA local não terminou de responder; tentando de novo.", falha.esperar(), "A IA local não conseguiu "
                            + "responder em nenhuma tentativa (" + falha.getMessage() + ") e o processamento parou aqui, "
                            + "sem " + semIsso(oQue) + ". Clique em Retomar.");
            case MODELO_INEXISTENTE -> ResultadoDaTarefa.pausar("IA_LOCAL_SEM_MODELO", "O modelo " + nomeDoModelo
                    + " não está instalado na IA local (Ollama). Avise o administrador (configuração "
                    + "atendimento.ia.local.modelo) e clique em Retomar depois de instalar.");
            case RESPOSTA_INVALIDA -> ResultadoDaTarefa.definitiva("IA_LOCAL_RESPOSTA_INVALIDA", "A IA local respondeu "
                    + "fora do formato duas vezes seguidas. Clique em Tentar de novo.");
            case ARQUIVO_FALHOU -> ResultadoDaTarefa.definitiva("IA_LOCAL_ARQUIVO_FALHOU", "A IA local não conseguiu "
                    + "ler este arquivo (" + falha.getMessage() + "). Confira se ele abre no computador; se abrir, tente "
                    + "de novo ou envie em outro formato.");
            case PEDIDO_RECUSADO, BLOQUEOU -> ResultadoDaTarefa.definitiva("IA_LOCAL_RECUSOU", falha.getMessage());
            case CHAVE_RECUSADA, COTA_DIARIA, SEM_CREDITO -> ResultadoDaTarefa.definitiva("IA_LOCAL_RECUSOU",
                    falha.getMessage());
        };
    }
}
