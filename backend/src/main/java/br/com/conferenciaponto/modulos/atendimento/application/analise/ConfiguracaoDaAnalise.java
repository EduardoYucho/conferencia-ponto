package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * O que a análise usa da configuração ({@code atendimento.gemini} no atendimento.yml): os modelos ficam na
 * configuração, não no código, para trocar (ou comparar) sem mexer no sistema.
 *
 * @param temperatura       vazio = a padrão do modelo (nos Gemini 3 o Google recomenda não baixar)
 * @param raciocinio        nível de raciocínio (ex.: low); vazio = o padrão do modelo
 * @param quadrosPorSegundo quantos quadros por segundo o Gemini olha no vídeo (1 é o padrão do Google; 2 ou 3 não
 *                          perdem ações rápidas, mas custam mais)
 * @param videoMaximo       o maior vídeo que vai ao Gemini, em bytes (2 GB é o limite da Files API)
 * @param esperaVideo       quanto esperar o Google preparar o vídeo enviado
 */
@Component
public record ConfiguracaoDaAnalise(String modeloDeAnalise, String modeloDeAudio, int tentativas, long inlineMaximo,
                                    int maxTokensSaida, Double temperatura, String raciocinio, double quadrosPorSegundo,
                                    long videoMaximo, Duration esperaVideo) {

    public static final double QUADROS_POR_SEGUNDO_PADRAO = 1.0;
    public static final long VIDEO_MAXIMO_PADRAO = DataSize.ofGigabytes(2).toBytes();

    @Autowired
    public ConfiguracaoDaAnalise(@Value("${atendimento.gemini.modelos.analise:gemini-3.8-flash}") String modeloDeAnalise,
                                 @Value("${atendimento.gemini.modelos.audio:gemini-3.8-flash}") String modeloDeAudio,
                                 @Value("${atendimento.gemini.tentativas:5}") int tentativas,
                                 @Value("${atendimento.gemini.inline-maximo:15MB}") DataSize inlineMaximo,
                                 @Value("${atendimento.gemini.max-tokens-saida:32768}") int maxTokensSaida,
                                 @Value("${atendimento.gemini.temperatura:}") String temperatura,
                                 @Value("${atendimento.gemini.raciocinio:}") String raciocinio,
                                 @Value("${atendimento.gemini.video.quadros-por-segundo:1}") double quadrosPorSegundo,
                                 @Value("${atendimento.gemini.video.tamanho-maximo:2GB}") DataSize videoMaximo,
                                 @Value("${atendimento.gemini.video.espera:15m}") Duration esperaVideo) {
        this(modeloDeAnalise.strip(), modeloDeAudio.strip(), Math.max(1, tentativas), inlineMaximo.toBytes(),
                maxTokensSaida, temperatura == null || temperatura.isBlank() ? null : Double.valueOf(temperatura.strip()),
                raciocinio == null || raciocinio.isBlank() ? null : raciocinio.strip(),
                quadrosPorSegundo > 0 ? Math.min(quadrosPorSegundo, 24) : QUADROS_POR_SEGUNDO_PADRAO,
                videoMaximo.toBytes(), esperaVideo);
    }

    /** Sem os campos do vídeo (os padrões: 1 quadro por segundo, até 2 GB, 15 min de espera). */
    public ConfiguracaoDaAnalise(String modeloDeAnalise, String modeloDeAudio, int tentativas, long inlineMaximo,
                                 int maxTokensSaida, Double temperatura, String raciocinio) {
        this(modeloDeAnalise, modeloDeAudio, tentativas, inlineMaximo, maxTokensSaida, temperatura, raciocinio,
                QUADROS_POR_SEGUNDO_PADRAO, VIDEO_MAXIMO_PADRAO, Duration.ofMinutes(15));
    }

    /** O modelo que vai na chamada de cada tipo de arquivo (o áudio pode usar um modelo de transcrição). */
    public String modelo(CategoriaDoArquivo categoria) {
        return categoria == CategoriaDoArquivo.AUDIO ? modeloDeAudio : modeloDeAnalise;
    }

    /**
     * Como a análise fica guardada (e é reconhecida como atual): o modelo e, no vídeo com outra taxa de quadros, a
     * taxa ("gemini-3.8-flash (2 quadros/s)"). Mudar a taxa na configuração faz o vídeo ser analisado de novo na
     * próxima vez que a pessoa processar.
     */
    public String identificacao(CategoriaDoArquivo categoria) {
        String modelo = modelo(categoria);
        if (categoria != CategoriaDoArquivo.VIDEO || quadrosPorSegundo == QUADROS_POR_SEGUNDO_PADRAO) {
            return modelo;
        }
        String taxa = quadrosPorSegundo == Math.rint(quadrosPorSegundo) ? String.valueOf((long) quadrosPorSegundo)
                : String.valueOf(quadrosPorSegundo).replace('.', ',');
        return modelo + " (" + taxa + " quadros/s)";
    }
}
