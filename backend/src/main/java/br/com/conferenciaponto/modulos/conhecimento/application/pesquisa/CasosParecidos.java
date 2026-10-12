package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.ConfiguracaoDosVetores;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.VetoresDaBase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.TextoDoVetor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * O aviso de caso parecido, na tela dos chamados para o desenvolvimento (antes de copiar ou confirmar): até
 * {@code conhecimento.parecidos.limite} registros parecidos pelo significado (acima de
 * {@code conhecimento.parecidos.similaridade-minima}) ou da mesma tela, com a situação e o número do chamado, para não
 * abrir um chamado repetido. Os registros do próprio atendimento não entram. Sem a chave do Gemini, só os da mesma
 * tela, com aviso.
 */
@Service
public class CasosParecidos {

    private static final double BONUS_DA_MESMA_TELA = 0.1;

    private final Registros registros;
    private final VetoresDaBase vetores;
    private final ConfiguracaoDosVetores configuracao;

    public CasosParecidos(Registros registros, VetoresDaBase vetores, ConfiguracaoDosVetores configuracao) {
        this.registros = registros;
        this.vetores = vetores;
        this.configuracao = configuracao;
    }

    /**
     * O caso que a pessoa está escrevendo.
     *
     * @param texto         o sintoma, a mensagem de erro e a causa (o que houver)
     * @param atendimentoId o atendimento do caso (os registros dele não contam como parecidos)
     */
    public record Pedido(String titulo, String texto, String telaCodigo, UUID atendimentoId) {
    }

    /**
     * @param similaridade o quanto é parecido pelo significado (0 a 1), ou null se entrou só pela tela
     * @param mesmaTela    o código da tela é o mesmo
     */
    public record Parecido(UUID id, String titulo, String tipo, String tipoRotulo, String situacao, String situacaoRotulo,
                           String chamadoDev, String versaoCorrecao, String telaNome, String telaCodigo, Double similaridade,
                           boolean mesmaTela) {
    }

    /** @param aviso por que a comparação pelo significado ficou de fora (sem chave...), ou null */
    public record Resultado(List<Parecido> registros, boolean porSignificado, String aviso) {
    }

    public Resultado procurar(Usuario usuario, Pedido pedido) {
        if (pedido == null) {
            return new Resultado(List.of(), false, null);
        }
        int limite = Math.max(1, configuracao.limiteDeParecidos());
        Map<UUID, Double> similaridades = new LinkedHashMap<>();
        boolean porSignificado = false;
        String aviso = null;
        String titulo = TextoDoVetor.limpoOuNulo(pedido.titulo());
        String texto = TextoDoVetor.limpoOuNulo(pedido.texto());
        if (titulo != null || texto != null) {
            VetoresDaBase.Consulta consulta = vetores.consulta(usuario.id(),
                    TextoDoVetor.documento(titulo, texto, null, null, null));
            porSignificado = consulta.ativa();
            aviso = consulta.aviso();
            if (consulta.ativa()) {
                for (Semelhante s : vetores.semelhantes(consulta.vetor(), configuracao.similaridadeDeParecido(), limite * 4, null)) {
                    similaridades.put(s.registroId(), s.similaridade());
                }
            }
        }
        String codigo = pedido.telaCodigo() == null || pedido.telaCodigo().isBlank() ? null : pedido.telaCodigo().strip();
        List<UUID> daMesmaTela = registros.comCodigoDeTela(codigo, limite * 4);
        List<UUID> ids = new java.util.ArrayList<>(similaridades.keySet());
        daMesmaTela.stream().filter(id -> !similaridades.containsKey(id)).forEach(ids::add);
        List<Parecido> parecidos = registros.resumos(ids).stream()
                .filter(r -> pedido.atendimentoId() == null || !pedido.atendimentoId().equals(r.atendimentoId()))
                .map(r -> new Parecido(r.id(), r.titulo(), r.tipo().codigo(), r.tipo().rotulo(), r.situacao().codigo(),
                        r.situacao().rotulo(), r.chamadoDev(), r.versaoCorrecao(), r.telaNome(), r.telaCodigo(),
                        similaridades.get(r.id()), codigo != null && r.telaCodigo() != null
                        && r.telaCodigo().strip().toLowerCase(Locale.ROOT).equals(codigo.toLowerCase(Locale.ROOT))))
                .sorted(Comparator.comparingDouble(CasosParecidos::pontos).reversed()
                        .thenComparing(p -> ids.indexOf(p.id())))
                .limit(limite)
                .toList();
        return new Resultado(parecidos, porSignificado, aviso);
    }

    private static double pontos(Parecido p) {
        return Objects.requireNonNullElse(p.similaridade(), 0.0) + (p.mesmaTela() ? BONUS_DA_MESMA_TELA : 0);
    }
}
