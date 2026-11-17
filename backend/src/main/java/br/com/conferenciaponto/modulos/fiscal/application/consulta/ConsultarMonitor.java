package br.com.conferenciaponto.modulos.fiscal.application.consulta;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.ConfiguracaoDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.Alvo;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.EstadoAtual;
import br.com.conferenciaponto.modulos.fiscal.domain.Incidente;
import br.com.conferenciaponto.modulos.fiscal.domain.MapaDosEstados;
import br.com.conferenciaponto.modulos.fiscal.domain.NomesDosAutorizadores;
import br.com.conferenciaponto.modulos.fiscal.domain.ProblemaLocal;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O que a tela mostra: o quadro (estado × documento, com quem autoriza cada um), cada autorizador com os serviços,
 * os incidentes e a disponibilidade de um período, as fontes e os avisos dos portais. Só lê.
 */
@Component
public class ConsultarMonitor {

    /** Os períodos que a tela oferece (24 horas a 90 dias, o prazo dos resumos por hora). */
    public static final int DIAS_MAXIMO = 90;
    static final String AINDA_SEM_LEITURA = "Ainda sem leitura.";

    // ------------------------------------------------------------------------------------------------- views

    public record DocumentoView(String codigo, String rotulo) {
    }

    public record RodadaView(Instant inicio, Instant fim, boolean semInternet, int fontesComProblema) {
    }

    /** Um autorizador visto de um estado (uma célula do quadro). */
    public record CelulaView(String documento, String autorizador, String nome, Situacao situacao, Instant desde,
                             String detalhe, Instant lidaEm) {
    }

    public record EstadoView(String uf, List<CelulaView> celulas, CelulaView contingencia) {
    }

    public record ServicoView(String servico, Situacao situacao, String detalhe, Integer cstat, Integer tempoMs,
                              Instant verificadoEm, Instant desde) {
    }

    public record IncidenteAbertoView(Instant inicio, Situacao pior, String detalhe) {
    }

    /**
     * @param estados os estados que ele autoriza neste documento (ou que usam esta contingência)
     * @param origem  painel_svrs ou teste_conexao
     */
    public record AutorizadorView(String documento, String rotuloDocumento, String autorizador, String nome,
                                  boolean contingencia, Situacao situacao, Instant desde, String detalhe,
                                  Instant lidaEm, String origem, Integer tempoMs, List<String> estados,
                                  List<ServicoView> servicos, IncidenteAbertoView incidente) {
    }

    public record FonteView(String nome, String rotulo, Instant ultimaTentativa, Instant ultimoSucesso, String erro) {
    }

    public record AvisoView(String documento, String rotuloDocumento, String texto, Instant desde) {
    }

    public record ContagemView(int normal, int instavel, int fora, int semInformacao) {
    }

    /**
     * @param redeOscilando na última leitura, muitos servidores ficaram sem resposta ao mesmo tempo (a internet deste
     *                      computador, não a SEFAZ): a tela avisa no alto
     */
    public record PainelView(Instant agora, boolean coletaAtiva, long intervaloMinutos, RodadaView ultimaRodada,
                             List<DocumentoView> documentos, List<EstadoView> estados,
                             List<AutorizadorView> autorizadores, ContagemView contagem, List<FonteView> fontes,
                             List<AvisoView> avisos, boolean redeOscilando) {
    }

    public record IncidenteView(long id, String documento, String rotuloDocumento, String autorizador, String nome,
                                List<String> estados, Instant inicio, Instant fim, long duracaoMinutos, Situacao pior,
                                String detalhe, int leituras, boolean aberto) {
    }

    public record DisponibilidadeView(String documento, String rotuloDocumento, String autorizador, String nome,
                                      List<String> estados, Double percentual, int leituras, int ruins,
                                      int semInformacao, Integer tempoMedioMs, Integer tempoMaxMs) {
    }

    // ---------------------------------------------------------------------------------------------------------

    private final RegistroDoMonitor registro;
    private final MapaDosEstados mapa;
    private final ConfiguracaoDoMonitor configuracao;
    private final Clock clock;

    public ConsultarMonitor(RegistroDoMonitor registro, MapaDosEstados mapa, ConfiguracaoDoMonitor configuracao,
                            Clock clock) {
        this.registro = registro;
        this.mapa = mapa;
        this.configuracao = configuracao;
        this.clock = clock;
    }

    public PainelView painel() {
        Instant agora = clock.instant();
        Map<String, EstadoAtual> estados = registro.estados();
        Map<String, Incidente> abertos = registro.incidentesAbertos();

        List<EstadoView> quadro = new ArrayList<>();
        for (String uf : MapaDosEstados.UFS) {
            List<CelulaView> celulas = new ArrayList<>();
            for (Documento d : Documento.values()) {
                mapa.autorizador(d, uf).ifPresent(a -> celulas.add(celula(d, a, estados)));
            }
            CelulaView contingencia = mapa.contingencia(uf).map(svc -> celula(Documento.NFE, svc, estados)).orElse(null);
            quadro.add(new EstadoView(uf, celulas, contingencia));
        }

        List<AutorizadorView> autorizadores = new ArrayList<>();
        for (Map.Entry<Documento, Set<String>> e : todosOsAutorizadores(estados).entrySet()) {
            for (String a : e.getValue()) {
                autorizadores.add(autorizador(e.getKey(), a, estados, abertos));
            }
        }
        int normal = 0;
        int instavel = 0;
        int fora = 0;
        int semInformacao = 0;
        for (AutorizadorView a : autorizadores) {
            switch (a.situacao()) {
                case NORMAL -> normal++;
                case INSTAVEL -> instavel++;
                case FORA -> fora++;
                case SEM_INFORMACAO -> semInformacao++;
            }
        }

        RodadaView ultima = registro.ultimaRodada().map(r -> new RodadaView(r.inicio(), r.fim(), r.semInternet(),
                r.fontesComProblema())).orElse(null);
        List<FonteView> fontes = registro.fontes().stream().map(f -> new FonteView(f.nome(), rotuloDaFonte(f.nome()),
                f.ultimaTentativa(), f.ultimoSucesso(), f.erro())).toList();
        List<AvisoView> avisos = registro.avisos().stream().map(a -> new AvisoView(a.documento().name(),
                a.documento().rotulo(), a.texto(), a.desde())).toList();
        List<DocumentoView> documentos = java.util.Arrays.stream(Documento.values())
                .map(d -> new DocumentoView(d.name(), d.rotulo())).toList();
        boolean redeOscilando = estados.values().stream()
                .anyMatch(e -> ProblemaLocal.REDE_OSCILANDO.equals(e.leitura().detalhe()));
        return new PainelView(agora, configuracao.coleta().ativa(), configuracao.coleta().intervalo().toMinutes(),
                ultima, documentos, quadro, autorizadores, new ContagemView(normal, instavel, fora, semInformacao),
                fontes, avisos, redeOscilando);
    }

    public List<IncidenteView> incidentes(int dias) {
        Instant agora = clock.instant();
        return registro.incidentesDesde(desde(dias, agora)).stream().map(i -> new IncidenteView(i.id(),
                i.alvo().documento().name(), i.alvo().documento().rotulo(), i.alvo().autorizador(),
                NomesDosAutorizadores.nome(i.alvo().autorizador()), estadosDe(i.alvo().documento(), i.alvo().autorizador()),
                i.inicio(), i.fim(), i.duracao(agora).toMinutes(), i.pior(), i.detalhe(), i.leituras(), i.aberto()))
                .toList();
    }

    public List<DisponibilidadeView> disponibilidade(int dias) {
        Instant agora = clock.instant();
        return registro.disponibilidadeDesde(desde(dias, agora)).stream()
                .map(d -> new DisponibilidadeView(d.documento().name(), d.documento().rotulo(), d.autorizador(),
                        NomesDosAutorizadores.nome(d.autorizador()), estadosDe(d.documento(), d.autorizador()),
                        d.percentual(), d.leituras(), d.ruins(), d.semInformacao(), d.tempoMedioMs(), d.tempoMaxMs()))
                .sorted(Comparator.comparing(DisponibilidadeView::documento,
                                Comparator.comparingInt(c -> Documento.valueOf(c).ordinal()))
                        .thenComparing(v -> v.percentual() == null ? 101.0 : v.percentual())
                        .thenComparing(DisponibilidadeView::autorizador))
                .toList();
    }

    // ------------------------------------------------------------------------------------------------ apoio

    private static Instant desde(int dias, Instant agora) {
        if (dias < 1 || dias > DIAS_MAXIMO) {
            throw new RegraNegocioException("PERIODO_INVALIDO",
                    "O período vai de 1 a %d dias.".formatted(DIAS_MAXIMO));
        }
        return agora.minus(Duration.ofDays(dias));
    }

    /** Os do mapa, as contingências e qualquer outro que já tenha leitura (ex.: saiu do mapa há pouco). */
    private Map<Documento, Set<String>> todosOsAutorizadores(Map<String, EstadoAtual> estados) {
        Map<Documento, Set<String>> todos = new LinkedHashMap<>();
        Map<Documento, Set<String>> doMapa = mapa.autorizadores();
        for (Documento d : Documento.values()) {
            Set<String> deste = new java.util.TreeSet<>(doMapa.getOrDefault(d, Set.of()));
            if (d == Documento.NFE) {
                MapaDosEstados.UFS.forEach(uf -> mapa.contingencia(uf).ifPresent(deste::add));
            }
            estados.values().stream().map(EstadoAtual::alvo)
                    .filter(a -> a.documento() == d && a.ehGeral())
                    .forEach(a -> deste.add(a.autorizador()));
            todos.put(d, new LinkedHashSet<>(deste));
        }
        return todos;
    }

    private CelulaView celula(Documento d, String autorizador, Map<String, EstadoAtual> estados) {
        EstadoAtual e = estados.get(Alvo.geral(d, autorizador).chave());
        String nome = NomesDosAutorizadores.nome(autorizador);
        if (e == null) {
            return new CelulaView(d.name(), autorizador, nome, Situacao.SEM_INFORMACAO, null, AINDA_SEM_LEITURA, null);
        }
        return new CelulaView(d.name(), autorizador, nome, e.situacao(), e.desde(), e.leitura().detalhe(), e.lidaEm());
    }

    private AutorizadorView autorizador(Documento d, String a, Map<String, EstadoAtual> estados,
                                        Map<String, Incidente> abertos) {
        Alvo geral = Alvo.geral(d, a);
        EstadoAtual e = estados.get(geral.chave());
        List<ServicoView> servicos = estados.values().stream()
                .filter(s -> s.alvo().documento() == d && s.alvo().autorizador().equals(a) && !s.alvo().ehGeral())
                .sorted(Comparator.comparing(s -> s.alvo().servico()))
                .map(s -> new ServicoView(s.alvo().servico(), s.situacao(), s.leitura().detalhe(), s.leitura().cstat(),
                        s.leitura().tempoMs(), s.leitura().verificadoEm(), s.desde()))
                .toList();
        Incidente aberto = abertos.get(geral.chave());
        boolean contingencia = a.startsWith("SVC-");
        return new AutorizadorView(d.name(), d.rotulo(), a, NomesDosAutorizadores.nome(a), contingencia,
                e == null ? Situacao.SEM_INFORMACAO : e.situacao(), e == null ? null : e.desde(),
                e == null ? AINDA_SEM_LEITURA : e.leitura().detalhe(), e == null ? null : e.lidaEm(),
                e == null ? null : e.leitura().origem().codigo(), e == null ? null : e.leitura().tempoMs(),
                estadosDe(d, a), servicos,
                aberto == null ? null : new IncidenteAbertoView(aberto.inicio(), aberto.pior(), aberto.detalhe()));
    }

    private List<String> estadosDe(Documento d, String autorizador) {
        return autorizador.startsWith("SVC-") && d == Documento.NFE ? mapa.estadosDaContingencia(autorizador)
                : mapa.estados(d, autorizador);
    }

    /** "painel-svrs-nfe" → "Painel da SVRS · NF-e". */
    static String rotuloDaFonte(String nome) {
        if (nome.startsWith("painel-svrs-")) {
            return Documento.doCodigo(nome.substring("painel-svrs-".length()))
                    .map(d -> "Painel da SVRS · " + d.rotulo()).orElse(nome);
        }
        return "testes-de-conexao".equals(nome) ? "Testes de conexão" : nome;
    }
}
