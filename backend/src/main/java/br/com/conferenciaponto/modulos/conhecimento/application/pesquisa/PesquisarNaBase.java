package br.com.conferenciaponto.modulos.conhecimento.application.pesquisa;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.ConsultaDeTexto;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Encontrado;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Pesquisa;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Trecho;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.TrechoDestacado;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.TipoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.ConfiguracaoDosVetores;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.VetoresDaBase;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.Semelhante;
import br.com.conferenciaponto.modulos.conhecimento.domain.vetor.TextoDoVetor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * A pesquisa da base: a exata (texto em português sem diferença de acento, com o trecho que bateu destacado, pedaço de
 * código de tela, mensagem de erro parecida) junto com a por significado (o vetor da pesquisa, com a chave de quem
 * pesquisa, comparado com o de cada registro), fundidas pela posição em cada uma; e os filtros. Sem a chave do Gemini
 * (ou com o Gemini fora), fica só a exata, com aviso. Toda a base é visível para quem pesquisa.
 */
@Service
public class PesquisarNaBase {

    public static final int TAMANHO_DA_PAGINA = 20;
    static final int LIMITE_DO_FILTRO = 150;
    private static final int TAMANHO_DO_TRECHO = 220;

    private final Registros registros;
    private final Clock clock;
    private final VetoresDaBase vetores;
    private final ConfiguracaoDosVetores configuracao;

    @Autowired
    public PesquisarNaBase(Registros registros, Clock clock, VetoresDaBase vetores, ConfiguracaoDosVetores configuracao) {
        this.registros = registros;
        this.clock = clock;
        this.vetores = vetores;
        this.configuracao = configuracao;
    }

    /** Só a pesquisa exata. */
    public PesquisarNaBase(Registros registros, Clock clock) {
        this(registros, clock, null, null);
    }

    public record AutorView(java.util.UUID id, String nome) {
    }

    public ResultadoDaPesquisa pesquisar(Usuario usuario, FiltrosDaPesquisa filtros) {
        FiltrosDaPesquisa f = filtros == null
                ? new FiltrosDaPesquisa(null, null, null, null, null, null, null, null, false, 1) : filtros;
        TipoDoRegistro tipo = vazio(f.tipo()) ? null : TipoDoRegistro.doCodigo(f.tipo()).orElseThrow(() ->
                new RegraNegocioException("FILTRO_INVALIDO", "Tipo desconhecido: use dúvida, erro ou alteração."));
        SituacaoDoRegistro situacao = vazio(f.situacao()) ? null : SituacaoDoRegistro.doCodigo(f.situacao())
                .orElseThrow(() -> new RegraNegocioException("FILTRO_INVALIDO", "Situação desconhecida."));
        if (f.de() != null && f.ate() != null && f.de().isAfter(f.ate())) {
            throw new RegraNegocioException("PERIODO_INVALIDO", "A data inicial é depois da data final.");
        }
        ZoneId fuso = clock.getZone();
        ConsultaDeTexto texto = ConsultaDeTexto.de(f.q());
        int pagina = f.pagina() == null ? 1 : Math.max(1, f.pagina());
        List<Semelhante> porSignificado = List.of();
        ResultadoDaPesquisa.Significado significado = null;
        if (vetores != null && !texto.vazia()) {
            VetoresDaBase.Consulta consulta = vetores.consulta(usuario.id(), TextoDoVetor.consulta(f.q()));
            if (consulta.ativa()) {
                porSignificado = vetores.semelhantes(consulta.vetor(), configuracao.similaridadeNaPesquisa(),
                        configuracao.maximoNaPesquisa(), null);
            }
            significado = new ResultadoDaPesquisa.Significado(consulta.ativa(), consulta.aviso(), vetores.pendentes());
        }
        Pesquisa pesquisa = new Pesquisa(texto, tipo, situacao, filtro(f.cliente()), filtro(f.modulo()), f.autor(),
                f.de() == null ? null : f.de().atStartOfDay(fuso).toInstant(),
                f.ate() == null ? null : f.ate().plusDays(1).atStartOfDay(fuso).toInstant(), f.validadas(), pagina,
                TAMANHO_DA_PAGINA, porSignificado);
        Registros.Pagina achados = registros.pesquisar(pesquisa);
        List<RegistroEncontrado> lista = achados.registros().stream().map(e -> encontrado(e, texto)).toList();
        int paginas = (int) Math.max(1, (achados.total() + TAMANHO_DA_PAGINA - 1) / TAMANHO_DA_PAGINA);
        return new ResultadoDaPesquisa(lista, achados.total(), pagina, paginas, TAMANHO_DA_PAGINA, !texto.vazia(),
                significado);
    }

    /** Quem já escreveu algum registro (o filtro por autor). */
    public List<AutorView> autores() {
        return registros.autores().stream().map(a -> new AutorView(a.id(), a.nome())).toList();
    }

    private static RegistroEncontrado encontrado(Encontrado e, ConsultaDeTexto texto) {
        Registro r = e.registro();
        Encontrado.Destaque titulo = e.titulo();
        Trecho doTitulo = titulo.destacado() == null ? Trecho.simples(titulo.original())
                : TrechoDestacado.de(titulo.original(), titulo.semAcento(), titulo.destacado());
        Encontrado.Destaque trecho = e.trecho();
        Trecho doTrecho = trecho.destacado() == null || trecho.destacado().indexOf(TrechoDestacado.INICIO) < 0
                ? TrechoDestacado.inicio(trecho.original(), TAMANHO_DO_TRECHO)
                : TrechoDestacado.de(trecho.original(), trecho.semAcento(), trecho.destacado());
        Trecho codigo = texto.vazia() ? Trecho.simples(r.dados().telaCodigo())
                : TrechoDestacado.comPedacos(r.dados().telaCodigo(), texto.pedacosDeCodigo());
        return new RegistroEncontrado(r.id(), r.dados().tipo().codigo(), r.dados().tipo().rotulo(), r.situacao().codigo(),
                r.situacao().rotulo(), doTitulo.partes(), r.dados().telaNome(), codigo.partes(), r.dados().cliente(),
                r.dados().modulo(), r.chamadoDev(), r.versaoCorrecao(), r.solucaoValidada(), r.autorNome(), r.criadoEm(),
                doTrecho.partes(), !e.textual() && e.similaridade() != null, e.similaridade());
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }

    private static String filtro(String valor) {
        if (vazio(valor)) {
            return null;
        }
        String limpo = valor.replaceAll("\\s+", " ").strip();
        return limpo.length() > LIMITE_DO_FILTRO ? limpo.substring(0, LIMITE_DO_FILTRO) : limpo;
    }
}
