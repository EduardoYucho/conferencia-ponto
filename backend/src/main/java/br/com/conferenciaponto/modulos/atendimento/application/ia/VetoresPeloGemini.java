package br.com.conferenciaponto.modulos.atendimento.application.ia;

import br.com.conferenciaponto.modulos.atendimento.application.evento.FalhaNoVetor;
import br.com.conferenciaponto.modulos.atendimento.application.evento.VetoresDeTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Os vetores da base de conhecimento pelo modelo (a IA local, sem chave, ou o Gemini, com a chave da pessoa, aberta
 * só aqui, no gerador). Um uso que deu
 * certo deixa a chave como válida; a chave recusada e a cota do dia ficam marcadas na chave, como nas análises.
 */
@Component
public class VetoresPeloGemini implements VetoresDeTexto {

    static final String SEM_CHAVE = "Cadastre sua chave do Gemini (Atendimentos → Minha chave do Gemini) para usar a "
            + "pesquisa por significado.";

    private final ChamadasAoGemini chamadas;
    private final ChavesGemini chaves;
    private final ModeloDeIA modelo;
    private final Clock clock;

    public VetoresPeloGemini(ChamadasAoGemini chamadas, ChavesGemini chaves, ModeloDeIA modelo, Clock clock) {
        this.chamadas = chamadas;
        this.chaves = chaves;
        this.modelo = modelo;
        this.clock = clock;
    }

    @Override
    public float[] calcular(UUID usuarioId, String nomeDoModelo, int dimensao, String texto) {
        ChamadasAoGemini.ChaveDoDono chave = chamadas.chaveDo(usuarioId);
        if (!chave.aberta()) {
            String mensagem = chave.falha() instanceof ResultadoDaTarefa.FalhaDefinitiva f
                    && !"SEM_CHAVE_GEMINI".equals(f.codigo()) ? f.mensagem() : SEM_CHAVE;
            throw new FalhaNoVetor(FalhaNoVetor.Motivo.SEM_CHAVE, mensagem);
        }
        try {
            float[] vetor = modelo.vetor(chave.chave(), nomeDoModelo, dimensao, texto);
            chamadas.usoDeuCerto(usuarioId, chave);
            return vetor;
        } catch (FalhaDoModelo falha) {
            throw traduzir(falha, usuarioId, nomeDoModelo);
        }
    }

    private FalhaNoVetor traduzir(FalhaDoModelo falha, UUID usuarioId, String nomeDoModelo) {
        if (chamadas.local()) {
            return switch (falha.tipo()) {
                case MODELO_INEXISTENTE -> new FalhaNoVetor(FalhaNoVetor.Motivo.RECUSADO, "O modelo de vetores "
                        + nomeDoModelo + " não está instalado na IA local (Ollama). Avise o administrador "
                        + "(atendimento.ia.local.modelo-vetor).");
                case IA_LOCAL_PARADA, PLACA_OCUPADA, INDISPONIVEL, SEM_INTERNET, LIMITE ->
                        new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "A IA local não respondeu agora ("
                                + falha.getMessage() + ").");
                default -> new FalhaNoVetor(FalhaNoVetor.Motivo.RECUSADO, falha.getMessage());
            };
        }
        return switch (falha.tipo()) {
            case CHAVE_RECUSADA -> {
                chaves.atualizarSituacao(usuarioId, SituacaoDaChave.RECUSADA, clock.instant());
                yield new FalhaNoVetor(FalhaNoVetor.Motivo.CHAVE_RECUSADA, falha.getMessage() + " Confira ou cadastre de "
                        + "novo em Atendimentos → Minha chave do Gemini.");
            }
            case LIMITE -> new FalhaNoVetor(FalhaNoVetor.Motivo.LIMITE, "O Gemini pediu para esperar (limite por minuto).",
                    falha.esperar());
            case COTA_DIARIA -> {
                chaves.atualizarSituacao(usuarioId, SituacaoDaChave.SEM_COTA, clock.instant());
                yield new FalhaNoVetor(FalhaNoVetor.Motivo.COTA_DIARIA, "A cota diária da chave do Gemini acabou (renova "
                        + "por volta das 4h–5h).");
            }
            case SEM_CREDITO -> new FalhaNoVetor(FalhaNoVetor.Motivo.COTA_DIARIA, "Os créditos da chave do Gemini acabaram.");
            case INDISPONIVEL, SEM_INTERNET -> new FalhaNoVetor(FalhaNoVetor.Motivo.INDISPONIVEL, "O Gemini não respondeu "
                    + "agora (" + falha.getMessage() + ").");
            case MODELO_INEXISTENTE -> new FalhaNoVetor(FalhaNoVetor.Motivo.RECUSADO, "O modelo de vetores " + nomeDoModelo
                    + " não existe ou não está liberado para a chave. Avise o administrador (conhecimento.vetor.modelo).");
            default -> new FalhaNoVetor(FalhaNoVetor.Motivo.RECUSADO, falha.getMessage());
        };
    }
}
