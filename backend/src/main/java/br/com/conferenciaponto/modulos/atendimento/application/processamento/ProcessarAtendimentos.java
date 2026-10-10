package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.analise.EtapaDeAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoParaBaixar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.EstadoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.AcompanhamentoDaFila;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * O processamento de um atendimento: processar (baixar os anexos e analisar cada arquivo), cancelar, retomar e
 * tentar de novo um arquivo; e, como {@link AcompanhamentoDaFila}, a situação do atendimento e dos arquivos conforme
 * as tarefas terminam (o anexo baixado entra na fila da análise). Cada pessoa só mexe nos próprios atendimentos (o
 * de outra pessoa dá 404).
 */
@Service
public class ProcessarAtendimentos implements AcompanhamentoDaFila {

    private static final Logger log = LoggerFactory.getLogger(ProcessarAtendimentos.class);
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");
    static final String LINK_VENCIDO = "O link deste anexo venceu (o Digisac assina os links por 24 horas). Exporte a "
            + "conversa de novo no Digisac e envie o PDF neste atendimento, ou envie o arquivo à mão.";

    private final Atendimentos atendimentos;
    private final Arquivos arquivos;
    private final Tarefas tarefas;
    private final PublicadorDeProgresso publicador;
    /** As tarefas que usam o Gemini: a cota ou a chave param só estas (os downloads seguem). */
    private static final Set<TipoDeTarefa> TAREFAS_DE_IA = EnumSet.of(TipoDeTarefa.ANALISAR, TipoDeTarefa.REDIGIR);
    private static final Set<SituacaoDoAtendimento> FINAIS = EnumSet.of(SituacaoDoAtendimento.PRONTO, SituacaoDoAtendimento.COM_FALHAS);

    private final Clock clock;
    private final int tentativasDoDownload;
    private final EtapaDeAnalise analise;

    @Autowired
    public ProcessarAtendimentos(Atendimentos atendimentos, Arquivos arquivos, Tarefas tarefas,
                                 PublicadorDeProgresso publicador, Clock clock,
                                 @Value("${atendimento.download.tentativas:5}") int tentativasDoDownload,
                                 EtapaDeAnalise analise) {
        this.atendimentos = atendimentos;
        this.arquivos = arquivos;
        this.tarefas = tarefas;
        this.publicador = publicador;
        this.clock = clock;
        this.tentativasDoDownload = tentativasDoDownload;
        this.analise = analise;
    }

    /** Só os downloads (os testes da etapa de download). */
    public ProcessarAtendimentos(Atendimentos atendimentos, Arquivos arquivos, Tarefas tarefas,
                                 PublicadorDeProgresso publicador, Clock clock, int tentativasDoDownload) {
        this(atendimentos, arquivos, tarefas, publicador, clock, tentativasDoDownload, EtapaDeAnalise.NENHUMA);
    }

    /**
     * Põe na fila o download dos anexos que faltam (os de link vencido viram "vencido" na hora, sem rede) e a análise
     * dos arquivos prontos que ainda não têm.
     */
    public ProgressoView processar(Usuario usuario, UUID atendimentoId) {
        EstadoDoAtendimento estado = doDono(usuario, atendimentoId);
        if (estado.situacao() == SituacaoDoAtendimento.PAUSADO) {
            return retomar(usuario, atendimentoId);
        }
        Instant agora = clock.instant();
        int vencidos = arquivos.vencerLinks(atendimentoId, agora, LINK_VENCIDO);
        int criadas = 0;
        for (ArquivoParaBaixar anexo : arquivos.aBaixar(atendimentoId)) {
            if (tarefas.criar(TipoDeTarefa.BAIXAR, atendimentoId, anexo.id(), null, tentativasDoDownload, agora)) {
                arquivos.aguardar(anexo.id());
                criadas++;
            }
        }
        int analises = analise.pedirParaOAtendimento(atendimentoId, agora);
        if (tarefas.ativas(atendimentoId) > 0) {
            atendimentos.mudarSituacao(atendimentoId, SituacaoDoAtendimento.PROCESSANDO, null, agora);
        } else {
            atendimentos.mudarSituacao(atendimentoId, situacaoFinal(atendimentoId), null, agora);
        }
        log.info("Atendimento {} processando: {} download(s) e {} análise(s) na fila, {} link(s) vencido(s)", atendimentoId,
                criadas, analises, vencidos);
        publicador.publicar(atendimentoId);
        return progresso(atendimentoId);
    }

    /** Cancela o que ainda não começou (o que já está baixando termina). */
    public ProgressoView cancelar(Usuario usuario, UUID atendimentoId) {
        EstadoDoAtendimento estado = doDono(usuario, atendimentoId);
        if (estado.situacao() == SituacaoDoAtendimento.PROCESSANDO || estado.situacao() == SituacaoDoAtendimento.PAUSADO) {
            Instant agora = clock.instant();
            int canceladas = tarefas.cancelarDoAtendimento(atendimentoId, agora);
            atendimentos.mudarSituacao(atendimentoId, SituacaoDoAtendimento.CANCELADO, null, agora);
            log.info("Atendimento {} cancelado: {} tarefa(s) canceladas", atendimentoId, canceladas);
            publicador.publicar(atendimentoId);
        }
        return progresso(atendimentoId);
    }

    /** Retoma um atendimento pausado (ou processa de novo um cancelado). */
    public ProgressoView retomar(Usuario usuario, UUID atendimentoId) {
        EstadoDoAtendimento estado = doDono(usuario, atendimentoId);
        if (estado.situacao() != SituacaoDoAtendimento.PAUSADO) {
            return processar(usuario, atendimentoId);
        }
        Instant agora = clock.instant();
        int retomadas = tarefas.retomarDoAtendimento(atendimentoId, agora);
        atendimentos.mudarSituacao(atendimentoId, SituacaoDoAtendimento.PROCESSANDO, null, agora);
        log.info("Atendimento {} retomado: {} tarefa(s) de volta à fila", atendimentoId, retomadas);
        publicador.publicar(atendimentoId);
        return progresso(atendimentoId);
    }

    /** Tenta de novo o download de um anexo que falhou ou a análise de um arquivo pronto que ainda não tem. */
    public ProgressoView tentarDeNovo(Usuario usuario, UUID atendimentoId, UUID arquivoId) {
        EstadoDoAtendimento estado = doDono(usuario, atendimentoId);
        ArquivoDoAtendimento arquivo = arquivos.buscar(atendimentoId, arquivoId).orElseThrow(ProcessarAtendimentos::arquivoNaoEncontrado);
        SituacaoDoArquivo situacao = SituacaoDoArquivo.doCodigo(arquivo.situacao());
        Instant agora = clock.instant();
        if (situacao == SituacaoDoArquivo.PRONTO) {
            if (!analise.pedir(atendimentoId, arquivoId, agora)) {
                throw new RegraNegocioException("NADA_A_TENTAR", "Este arquivo já está pronto e analisado (ou a análise "
                        + "dele já está na fila).");
            }
            if (estado.situacao() == SituacaoDoAtendimento.PAUSADO) {
                tarefas.pausarDoAtendimento(atendimentoId, TAREFAS_DE_IA, agora); // volta junto com as outras no Retomar
            } else {
                atendimentos.mudarSituacao(atendimentoId, SituacaoDoAtendimento.PROCESSANDO, null, agora);
            }
            publicador.publicar(atendimentoId);
            return progresso(atendimentoId);
        }
        if (!arquivo.daConversa() || !Set.of(SituacaoDoArquivo.FALHOU, SituacaoDoArquivo.VENCIDO, SituacaoDoArquivo.AGUARDANDO)
                .contains(situacao)) {
            throw new RegraNegocioException("NADA_A_TENTAR", "Este arquivo não tem download para tentar de novo.");
        }
        ArquivoParaBaixar anexo = arquivos.paraBaixar(arquivoId).orElseThrow(ProcessarAtendimentos::arquivoNaoEncontrado);
        if (anexo.url() == null) {
            throw new RegraNegocioException("SEM_LINK", "Este anexo não tem link para download. Envie o arquivo à mão.");
        }
        if (anexo.validoAte() != null && !agora.isBefore(anexo.validoAte())) {
            arquivos.registrarFalha(arquivoId, SituacaoDoArquivo.VENCIDO, "LINK_VENCIDO", LINK_VENCIDO);
            throw new RegraNegocioException("LINK_VENCIDO", "O link deste anexo venceu em "
                    + DIA_E_HORA.format(anexo.validoAte().atZone(fuso())) + ". Exporte a conversa de novo no Digisac e envie "
                    + "o PDF neste atendimento, ou envie o arquivo à mão.");
        }
        arquivos.aguardar(arquivoId);
        tarefas.criar(TipoDeTarefa.BAIXAR, atendimentoId, arquivoId, null, tentativasDoDownload, agora);
        atendimentos.mudarSituacao(atendimentoId, SituacaoDoAtendimento.PROCESSANDO, null, agora);
        publicador.publicar(atendimentoId);
        return progresso(atendimentoId);
    }

    public ProgressoView progresso(Usuario usuario, UUID atendimentoId) {
        doDono(usuario, atendimentoId);
        return progresso(atendimentoId);
    }

    /** Um arquivo novo (ligação, vídeo, print): se o atendimento está processando, a análise dele entra na fila. */
    public void arquivoNovo(UUID atendimentoId, UUID arquivoId) {
        if (processando(atendimentoId)) {
            analise.pedir(atendimentoId, arquivoId, clock.instant());
        }
    }

    /** O conteúdo de um arquivo foi trocado (envio à mão): a análise antiga deixa de valer. */
    public void conteudoTrocado(UUID atendimentoId, UUID arquivoId) {
        analise.esquecer(arquivoId);
        if (processando(atendimentoId)) {
            analise.pedir(atendimentoId, arquivoId, clock.instant());
        }
    }

    /** Um arquivo saiu do atendimento: a análise dele também. */
    public void arquivoTirado(UUID arquivoId) {
        analise.esquecer(arquivoId);
    }

    private boolean processando(UUID atendimentoId) {
        return atendimentos.estado(atendimentoId).map(e -> e.situacao() == SituacaoDoAtendimento.PROCESSANDO).orElse(false);
    }

    /**
     * Depois de uma mudança fora da fila (arquivo enviado à mão ou tirado): se não há mais nada para fazer, o
     * atendimento que estava processando fica pronto (ou com falhas).
     */
    public void atualizarSituacao(UUID atendimentoId) {
        atendimentos.estado(atendimentoId).ifPresent(estado -> {
            if (estado.situacao() == SituacaoDoAtendimento.PROCESSANDO && tarefas.ativas(atendimentoId) == 0) {
                atendimentos.mudarSituacaoSe(atendimentoId, Set.of(SituacaoDoAtendimento.PROCESSANDO),
                        situacaoFinal(atendimentoId), null, clock.instant());
            } else if (FINAIS.contains(estado.situacao()) && situacaoFinal(atendimentoId) != estado.situacao()) {
                atendimentos.mudarSituacaoSe(atendimentoId, FINAIS, situacaoFinal(atendimentoId), null, clock.instant());
            }
        });
    }

    // ------------------------------------------------------------------------------------- acompanhamento da fila

    @Override
    public void aoComecar(Tarefa tarefa) {
        // o download avisa sozinho quando começa (marca "baixando"); a análise aparece como "analisando"
        if (tarefa.tipo() == TipoDeTarefa.ANALISAR) {
            // um "pronto" que chegou antes da hora (outra tarefa viu a fila vazia) volta a "processando"
            atendimentos.mudarSituacaoSe(tarefa.atendimentoId(), FINAIS, SituacaoDoAtendimento.PROCESSANDO, null, clock.instant());
            publicador.publicar(tarefa.atendimentoId());
        }
    }

    @Override
    public void antesDeConcluir(Tarefa tarefa) {
        if (tarefa.tipo() == TipoDeTarefa.BAIXAR && tarefa.arquivoId() != null) {
            analisarOBaixado(tarefa);
        }
    }

    @Override
    public void aoTerminar(Tarefa tarefa, ResultadoDaTarefa resultado) {
        if (tarefa.tipo() == TipoDeTarefa.BAIXAR && tarefa.arquivoId() != null) {
            registrarNoArquivo(tarefa, resultado);
        }
        if (resultado instanceof ResultadoDaTarefa.Pausar pausa) {
            Instant agora = clock.instant();
            if (TAREFAS_DE_IA.contains(tarefa.tipo())) {
                tarefas.pausarDoAtendimento(tarefa.atendimentoId(), TAREFAS_DE_IA, agora);
            } else {
                tarefas.pausarDoAtendimento(tarefa.atendimentoId(), agora);
            }
            // cancelado continua cancelado; de resto, a pausa vale (inclusive sobre um "pronto" que veio antes da hora)
            atendimentos.mudarSituacaoSe(tarefa.atendimentoId(), EnumSet.complementOf(EnumSet.of(SituacaoDoAtendimento.CANCELADO)),
                    SituacaoDoAtendimento.PAUSADO, pausa.mensagem(), agora);
            log.warn("Atendimento {} pausado: {}", tarefa.atendimentoId(), pausa.codigo());
        } else {
            atualizarSituacao(tarefa.atendimentoId());
        }
        publicador.publicar(tarefa.atendimentoId());
    }

    /**
     * O anexo baixado entra na fila da análise: pausada, se o atendimento está pausado; nada, se ele foi cancelado.
     * Chamado com o download ainda ativo, então a fila do atendimento não fica vazia entre uma etapa e outra; se
     * mesmo assim ele aparecer como pronto (vindo de antes), volta a "processando".
     */
    private void analisarOBaixado(Tarefa tarefa) {
        atendimentos.estado(tarefa.atendimentoId()).ifPresent(estado -> {
            if (estado.situacao() == SituacaoDoAtendimento.CANCELADO) {
                return;
            }
            Instant agora = clock.instant();
            if (!analise.pedir(tarefa.atendimentoId(), tarefa.arquivoId(), agora)) {
                return;
            }
            if (estado.situacao() == SituacaoDoAtendimento.PAUSADO) {
                tarefas.pausarDoAtendimento(tarefa.atendimentoId(), TAREFAS_DE_IA, agora);
            } else {
                // só desfaz um "pronto" antes da hora: se nesse meio-tempo pausou, a pausa fica
                atendimentos.mudarSituacaoSe(tarefa.atendimentoId(), FINAIS, SituacaoDoAtendimento.PROCESSANDO, null, agora);
            }
        });
    }

    private void registrarNoArquivo(Tarefa tarefa, ResultadoDaTarefa resultado) {
        if (resultado instanceof ResultadoDaTarefa.FalhaTemporaria t) {
            arquivos.registrarFalha(tarefa.arquivoId(), SituacaoDoArquivo.AGUARDANDO, t.codigo(),
                    "Tentativa " + tarefa.tentativa() + " de " + tarefa.maxTentativas() + " não deu certo: " + t.mensagem());
        } else if (resultado instanceof ResultadoDaTarefa.FalhaDefinitiva f) {
            SituacaoDoArquivo situacao = switch (f.codigo()) {
                case "LINK_VENCIDO" -> SituacaoDoArquivo.VENCIDO;
                case "ARQUIVO_GRANDE_DEMAIS", "TIPO_NAO_SUPORTADO" -> SituacaoDoArquivo.NAO_SUPORTADO;
                default -> SituacaoDoArquivo.FALHOU;
            };
            arquivos.registrarFalha(tarefa.arquivoId(), situacao, f.codigo(), f.mensagem());
        } else if (resultado instanceof ResultadoDaTarefa.Pausar p) {
            arquivos.registrarFalha(tarefa.arquivoId(), SituacaoDoArquivo.AGUARDANDO, p.codigo(), p.mensagem());
        }
    }

    // --------------------------------------------------------------------------------------------- apoio

    /** Com falhas: um anexo que não foi baixado ou uma análise que falhou de vez. */
    private SituacaoDoAtendimento situacaoFinal(UUID atendimentoId) {
        boolean comFalha = arquivos.doAtendimento(atendimentoId).stream()
                .filter(ArquivoDoAtendimento::daConversa)
                .anyMatch(a -> a.situacao().equals(SituacaoDoArquivo.FALHOU.codigo())
                        || a.situacao().equals(SituacaoDoArquivo.VENCIDO.codigo()));
        return comFalha || analise.panorama(atendimentoId).comFalha() ? SituacaoDoAtendimento.COM_FALHAS
                : SituacaoDoAtendimento.PRONTO;
    }

    private ProgressoView progresso(UUID atendimentoId) {
        return publicador.progresso(atendimentoId).orElseThrow(ProcessarAtendimentos::naoEncontrado);
    }

    private EstadoDoAtendimento doDono(Usuario usuario, UUID atendimentoId) {
        return atendimentos.estado(atendimentoId)
                .filter(e -> e.usuarioId().equals(usuario.id()))
                .orElseThrow(ProcessarAtendimentos::naoEncontrado);
    }

    private ZoneId fuso() {
        return clock.getZone();
    }

    static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO",
                "Atendimento não encontrado. Ele pode ter sido apagado.");
    }

    static RecursoNaoEncontradoException arquivoNaoEncontrado() {
        return new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO",
                "Arquivo não encontrado neste atendimento. Ele pode ter sido tirado.");
    }
}
