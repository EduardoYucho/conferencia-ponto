package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * O que a análise usa da configuração ({@code atendimento.gemini} no atendimento.yml): os modelos ficam na
 * configuração, não no código, para trocar (ou comparar) sem mexer no sistema.
 *
 * @param temperatura vazio = a padrão do modelo (nos Gemini 3 o Google recomenda não baixar)
 * @param raciocinio  nível de raciocínio (ex.: low); vazio = o padrão do modelo
 */
@Component
public record ConfiguracaoDaAnalise(String modeloDeAnalise, String modeloDeAudio, int tentativas, long inlineMaximo,
                                    int maxTokensSaida, Double temperatura, String raciocinio) {

    @Autowired
    public ConfiguracaoDaAnalise(@Value("${atendimento.gemini.modelos.analise:gemini-3.8-flash}") String modeloDeAnalise,
                                 @Value("${atendimento.gemini.modelos.audio:gemini-3.8-flash}") String modeloDeAudio,
                                 @Value("${atendimento.gemini.tentativas:5}") int tentativas,
                                 @Value("${atendimento.gemini.inline-maximo:15MB}") DataSize inlineMaximo,
                                 @Value("${atendimento.gemini.max-tokens-saida:32768}") int maxTokensSaida,
                                 @Value("${atendimento.gemini.temperatura:}") String temperatura,
                                 @Value("${atendimento.gemini.raciocinio:}") String raciocinio) {
        this(modeloDeAnalise.strip(), modeloDeAudio.strip(), Math.max(1, tentativas), inlineMaximo.toBytes(),
                maxTokensSaida, temperatura == null || temperatura.isBlank() ? null : Double.valueOf(temperatura.strip()),
                raciocinio == null || raciocinio.isBlank() ? null : raciocinio.strip());
    }

    /** O modelo de cada tipo de arquivo (o áudio pode usar um modelo de transcrição). */
    public String modelo(CategoriaDoArquivo categoria) {
        return categoria == CategoriaDoArquivo.AUDIO ? modeloDeAudio : modeloDeAnalise;
    }
}
