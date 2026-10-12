package br.com.conferenciaponto.modulos.atendimento.application.ia;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.CofreException;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * O que as tarefas que usam o Gemini têm em comum: a chave do dono do atendimento, a leitura do JSON da resposta e
 * o que fazer com cada falha (tabela 6.4 da arquitetura).
 */
@Component
public class ChamadasAoGemini {

    public static final String SEM_CHAVE = "Cadastre sua chave do Gemini em Atendimentos → Minha chave do Gemini e "
            + "clique em Processar de novo.";
    public static final String COTA_DIARIA = "A cota diária da sua chave do Gemini acabou. Ela renova por volta das "
            + "4h–5h (meia-noite no horário do Pacífico); depois disso, clique em Retomar e o processamento continua de "
            + "onde parou.";

    private final ChavesGemini chaves;
    private final Cofre cofre;
    private final ObjectMapper json;
    private final Clock clock;

    public ChamadasAoGemini(ChavesGemini chaves, Cofre cofre, ObjectMapper json, Clock clock) {
        this.chaves = chaves;
        this.cofre = cofre;
        this.json = json;
        this.clock = clock;
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

    /**
     * O resultado da tarefa para cada falha do Gemini. A chave recusada e a cota do dia pausam as tarefas de IA do
     * atendimento (os downloads seguem) até o Retomar.
     *
     * @param oQue o que a tarefa fazia, para a mensagem de bloqueio (ex.: "analisar este arquivo")
     */
    public ResultadoDaTarefa traduzir(FalhaDoModelo falha, UUID usuario, String nomeDoModelo, String oQue) {
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
        };
    }
}
