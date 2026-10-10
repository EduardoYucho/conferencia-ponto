package br.com.conferenciaponto.modulos.atendimento.application.chave;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveCifrada;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.VerificadorDeChave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A chave da API do Gemini de cada usuário: cada um cadastra, testa, troca e apaga a própria; ninguém (nem o
 * administrador) vê ou usa a de outro. A chave é testada com o Google antes de ser guardada, fica cifrada e
 * nunca volta para a tela nem vai para o log.
 */
@Service
public class GerenciarChaveGemini {

    private static final Logger log = LoggerFactory.getLogger(GerenciarChaveGemini.class);

    /**
     * Chaves do Google: letras, números, ".", "-" e "_", sem espaços. As do formato novo do AI Studio começam com
     * "AQ." (têm ponto); as antigas, com "AIza". Quem decide se a chave vale é o teste com o Google.
     */
    private static final Pattern FORMATO = Pattern.compile("[A-Za-z0-9._\\-]{20,300}");

    private final ChavesGemini chaves;
    private final Cofre cofre;
    private final VerificadorDeChave verificador;
    private final Clock clock;
    private final boolean exigirNivelPago;

    public GerenciarChaveGemini(ChavesGemini chaves, Cofre cofre, VerificadorDeChave verificador, Clock clock,
                                @Value("${atendimento.gemini.exigir-nivel-pago:true}") boolean exigirNivelPago) {
        this.chaves = chaves;
        this.cofre = cofre;
        this.verificador = verificador;
        this.clock = clock;
        this.exigirNivelPago = exigirNivelPago;
    }

    public ChaveGeminiView estado(Usuario usuario) {
        return chaves.buscar(usuario.id()).map(c -> view(c, null)).orElseGet(this::semChave);
    }

    /** Testa a chave com o Google e, se ele aceitar, guarda cifrada (substitui a anterior). */
    public ChaveGeminiView cadastrar(Usuario usuario, String chave, boolean nivelPagoConfirmado) {
        String limpa = chave == null ? "" : chave.strip();
        if (limpa.isEmpty()) {
            throw new RegraNegocioException("CHAVE_GEMINI_VAZIA", "Cole a chave da API do Gemini.");
        }
        if (!FORMATO.matcher(limpa).matches()) {
            throw new RegraNegocioException("CHAVE_GEMINI_FORMATO", "Isso não parece uma chave da API do Gemini: ela "
                    + "tem só letras, números, \".\", \"-\" e \"_\", sem espaços, e começa com \"AQ.\" ou \"AIza\". "
                    + "Copie de novo no Google AI Studio, pelo botão de copiar ao lado da chave.");
        }
        if (exigirNivelPago && !nivelPagoConfirmado) {
            throw new RegraNegocioException("NIVEL_PAGO_NAO_CONFIRMADO", "Confirme que a chave é de um projeto com "
                    + "faturamento ativo (nível pago). No nível gratuito, o Google pode usar o conteúdo enviado (conversas, "
                    + "áudios e telas de clientes) para melhorar os produtos dele.");
        }
        VerificadorDeChave.Resultado teste = verificador.testar(limpa);
        recusarSeNaoFuncionou(teste, true);
        SituacaoDaChave situacao = teste.tipo() == VerificadorDeChave.Tipo.SEM_COTA ? SituacaoDaChave.SEM_COTA
                : SituacaoDaChave.VALIDA;
        ChaveCifrada cifrada = cofre.cifrar(limpa, usuario.id());
        Instant agora = clock.instant();
        ChaveGemini nova = new ChaveGemini(usuario.id(), cifrada, limpa.substring(limpa.length() - 4),
                nivelPagoConfirmado, situacao, agora, agora);
        chaves.salvar(nova);
        log.info("Chave do Gemini cadastrada por {} ({})", usuario.login(), situacao.codigo());
        return view(nova, mensagem(teste));
    }

    /** Testa de novo a chave guardada e atualiza a situação. */
    public ChaveGeminiView testar(Usuario usuario) {
        ChaveGemini atual = chaves.buscar(usuario.id()).orElseThrow(() -> new RecursoNaoEncontradoException(
                "SEM_CHAVE_GEMINI", "Você ainda não cadastrou a sua chave do Gemini."));
        String chave = cofre.decifrar(atual.cifrada(), usuario.id());
        VerificadorDeChave.Resultado teste = verificador.testar(chave);
        recusarSeNaoFuncionou(teste, false);
        SituacaoDaChave situacao = switch (teste.tipo()) {
            case RECUSADA -> SituacaoDaChave.RECUSADA;
            case SEM_COTA -> SituacaoDaChave.SEM_COTA;
            default -> SituacaoDaChave.VALIDA;
        };
        Instant agora = clock.instant();
        chaves.atualizarSituacao(usuario.id(), situacao, agora);
        log.info("Chave do Gemini de {} testada: {}", usuario.login(), situacao.codigo());
        return view(atual.comSituacao(situacao, agora), mensagem(teste));
    }

    public ChaveGeminiView apagar(Usuario usuario) {
        chaves.apagar(usuario.id());
        log.info("Chave do Gemini apagada por {}", usuario.login());
        return semChave();
    }

    /**
     * Sem resposta do Google (fora do ar, sem internet) nada muda. No cadastro, uma chave recusada também não é
     * guardada; no teste da chave já guardada, "recusada" vira a situação dela.
     */
    private static void recusarSeNaoFuncionou(VerificadorDeChave.Resultado teste, boolean cadastro) {
        String detalhe = teste.detalhe() == null ? "" : " " + teste.detalhe();
        switch (teste.tipo()) {
            case INDISPONIVEL -> throw new RegraNegocioException("GEMINI_INDISPONIVEL", "Não foi possível testar a chave "
                    + "agora: o Google não respondeu como deveria." + detalhe + (cadastro ? " A chave não foi guardada." : "")
                    + " Tente de novo em alguns minutos.");
            case SEM_INTERNET -> throw new RegraNegocioException("SEM_INTERNET", "O servidor não conseguiu falar com o "
                    + "Google (sem internet ou bloqueado pela rede)." + (cadastro ? " A chave não foi guardada." : ""));
            case RECUSADA -> {
                if (cadastro) {
                    throw new RegraNegocioException("CHAVE_GEMINI_RECUSADA", "O Google recusou a chave." + detalhe
                            + " Ela não foi guardada.");
                }
            }
            default -> {
                // VALIDA ou SEM_COTA: segue
            }
        }
    }

    private static String mensagem(VerificadorDeChave.Resultado teste) {
        return switch (teste.tipo()) {
            case VALIDA -> "O Google aceitou a chave.";
            case SEM_COTA -> "O Google aceitou a chave, mas a cota dela acabou agora."
                    + (teste.detalhe() == null ? "" : " " + teste.detalhe());
            case RECUSADA -> "O Google recusou a chave." + (teste.detalhe() == null ? "" : " " + teste.detalhe())
                    + " Cadastre uma chave que funcione.";
            default -> null;
        };
    }

    private ChaveGeminiView view(ChaveGemini chave, String mensagem) {
        return new ChaveGeminiView(true, chave.ultimosCaracteres(), chave.nivelPagoConfirmado(),
                chave.situacao().codigo(), chave.testadaEm(), chave.atualizadaEm(), exigirNivelPago, mensagem);
    }

    private ChaveGeminiView semChave() {
        return new ChaveGeminiView(false, null, false, null, null, null, exigirNivelPago, null);
    }
}
