package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConversaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ResumoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.AnexoLido;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Cabecalho;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ConversaLida;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ExtratorDePdf;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeitorConversaDigisac;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.PdfPosicionado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Os atendimentos de cada pessoa: criar a partir do PDF do Digisac, listar, abrir e apagar. Cada um vê só os
 * próprios; o administrador libera o gerador, mas não abre o atendimento dos outros.
 *
 * <p>O log registra ids, contagens e códigos de recusa; nunca o conteúdo da conversa, nomes ou links.
 */
@Service
public class GerenciarAtendimentos {

    private static final Logger log = LoggerFactory.getLogger(GerenciarAtendimentos.class);

    private final ExtratorDePdf extrator;
    private final LeitorConversaDigisac leitor;
    private final Mascaramento mascaramento = new Mascaramento();
    private final Atendimentos atendimentos;
    private final PastaDosAtendimentos pasta;
    private final TransactionOperations transacao;
    private final Clock clock;
    private final long tamanhoMaximo;
    private final int diasArquivos;
    private final int diasTextos;

    public GerenciarAtendimentos(ExtratorDePdf extrator, Atendimentos atendimentos, PastaDosAtendimentos pasta,
                                 TransactionOperations transacao, Clock clock,
                                 @Value("${atendimento.pdf.tamanho-maximo:50MB}") DataSize tamanhoMaximo,
                                 @Value("${atendimento.retencao.dias-arquivos:30}") int diasArquivos,
                                 @Value("${atendimento.retencao.dias-textos:180}") int diasTextos) {
        this.extrator = extrator;
        this.leitor = new LeitorConversaDigisac(clock.getZone());
        this.atendimentos = atendimentos;
        this.pasta = pasta;
        this.transacao = transacao;
        this.clock = clock;
        this.tamanhoMaximo = tamanhoMaximo.toBytes();
        this.diasArquivos = diasArquivos;
        this.diasTextos = Math.max(diasTextos, diasArquivos);
    }

    /**
     * Lê o PDF enviado (em fluxo) e cria o atendimento. Se a pessoa já tem um atendimento do mesmo chamado, nada é
     * criado: a resposta traz o existente (a renovação dos links pelo PDF novo vem na próxima etapa).
     *
     * @param tamanhoInformado o Content-Length do envio, ou -1
     */
    public ResultadoDoEnvio receberPdf(Usuario usuario, InputStream corpo, long tamanhoInformado, String nomeDoArquivo) {
        if (tamanhoInformado > tamanhoMaximo) {
            throw LeituraDoPdfException.grandeDemais(DataSize.ofBytes(tamanhoMaximo).toMegabytes() + " MB");
        }
        Path recebido = pasta.receber(corpo, tamanhoMaximo);
        try {
            ConversaLida lida;
            try {
                PdfPosicionado pdf = extrator.extrair(recebido);
                try {
                    lida = leitor.ler(pdf);
                } catch (RuntimeException e) {
                    if (e instanceof LeituraDoPdfException) {
                        throw e;
                    }
                    // o leitor não previu algo deste PDF: a pessoa vê a mesma explicação de layout desconhecido.
                    // Só o tipo e o lugar do erro vão para o log (a mensagem da exceção pode ter texto do PDF).
                    StackTraceElement onde = e.getStackTrace().length == 0 ? null : e.getStackTrace()[0];
                    log.warn("O leitor do PDF falhou ({} em {}): tratado como layout desconhecido",
                            e.getClass().getSimpleName(), onde);
                    throw LeituraDoPdfException.layoutDesconhecido();
                }
            } catch (LeituraDoPdfException e) {
                log.info("PDF recusado na leitura: {}", e.getCodigo());
                throw e;
            }
            Mascaramento.Resultado mascarado = mascaramento.aplicar(lida);
            ConversaLida conversa = mascarado.conversa();
            Instant agora = clock.instant();
            LeituraView leitura = leitura(conversa, mascarado, agora, nomeDoArquivo);

            Optional<ResumoDoAtendimento> existente = atendimentos.doChamado(usuario.id(), conversa.cabecalho().chamado());
            if (existente.isPresent()) {
                log.info("PDF de um chamado que já tem o atendimento {}: nada criado", existente.get().id());
                return new ResultadoDoEnvio(false, ResumoView.de(existente.get(), agora), leitura);
            }

            UUID id = UUID.randomUUID();
            NovoAtendimento novo = new NovoAtendimento(id, usuario.id(), conversa, mascarado.omitidos(),
                    LeitorConversaDigisac.VERSAO, agora, agora.plus(diasArquivos, ChronoUnit.DAYS),
                    agora.plus(diasTextos, ChronoUnit.DAYS));
            try {
                transacao.executeWithoutResult(s -> {
                    atendimentos.salvarNovo(novo);
                    pasta.guardarPdf(id, recebido);
                });
            } catch (RuntimeException e) {
                apagarPastaSemFalhar(id);
                throw e;
            }
            log.info("Atendimento {} criado: {} mensagens, {} eventos, {} anexos, {} trechos omitidos, leitor v{}", id,
                    leitura.mensagens(), leitura.eventos(), leitura.anexos(), mascarado.omitidos().total(),
                    LeitorConversaDigisac.VERSAO);
            Cabecalho c = conversa.cabecalho();
            ResumoDoAtendimento resumo = new ResumoDoAtendimento(id, c.chamado(), c.contato(), c.inicio(), c.fim(),
                    SituacaoDoAtendimento.NOVO, agora, leitura.mensagens(), leitura.anexos(), conversa.linksValidosAte());
            return new ResultadoDoEnvio(true, ResumoView.de(resumo, agora), leitura);
        } finally {
            pasta.descartar(recebido); // depois de guardado, o temporário já não existe
        }
    }

    public List<ResumoView> listar(Usuario usuario) {
        Instant agora = clock.instant();
        return atendimentos.doUsuario(usuario.id()).stream().map(r -> ResumoView.de(r, agora)).toList();
    }

    public AtendimentoView detalhe(Usuario usuario, UUID id) {
        AtendimentoGuardado guardado = atendimentos.buscar(id, usuario.id()).orElseThrow(GerenciarAtendimentos::naoEncontrado);
        Instant agora = clock.instant();
        ResumoDoAtendimento r = guardado.resumo();
        ConversaGuardada conversa = guardado.conversa();
        List<AtendimentoView.Item> itens = conversa.itens().stream()
                .map(i -> new AtendimentoView.Item(i.ordem(), i.tipo().codigo(), i.lado() == null ? null : i.lado().codigo(),
                        i.remetente(), i.momento(), i.texto(), i.anexos()))
                .toList();
        List<AtendimentoView.Anexo> anexos = guardado.arquivos().stream()
                .map(a -> anexo(a, agora))
                .toList();
        return new AtendimentoView(r.id(), r.chamado(), r.contato(), r.inicio(), r.fim(), r.situacao().codigo(), r.criadoEm(),
                guardado.apagarArquivosEm(), conversa.cabecalho().assunto(), conversa.cabecalho().resumo(),
                r.linksValidosAte(), ResumoView.vencido(r.linksValidosAte(), agora), conversa.omitidos(),
                guardado.versaoLeitor(), itens, anexos);
    }

    /** Apaga o atendimento e os arquivos dele (primeiro os arquivos: se um estiver preso, nada é apagado). */
    public void apagar(Usuario usuario, UUID id) {
        if (!atendimentos.existe(id, usuario.id())) {
            throw naoEncontrado();
        }
        try {
            pasta.apagar(id);
        } catch (UncheckedIOException e) {
            log.warn("Não foi possível apagar os arquivos do atendimento {}: {}", id, e.getCause());
            throw new ConflitoException("ARQUIVOS_EM_USO", "Não foi possível apagar os arquivos do atendimento agora "
                    + "(algum pode estar aberto em outro programa). Feche-os e tente de novo.");
        }
        atendimentos.apagar(id, usuario.id());
        log.info("Atendimento {} apagado", id);
    }

    // ------------------------------------------------------------------------------------------ apoio

    private static LeituraView leitura(ConversaLida conversa, Mascaramento.Resultado mascarado, Instant agora,
                                       String nomeDoArquivo) {
        Map<String, Integer> porCategoria = new TreeMap<>();
        for (AnexoLido anexo : conversa.anexos()) {
            porCategoria.merge(anexo.categoria().codigo(), 1, Integer::sum);
        }
        Cabecalho c = conversa.cabecalho();
        Instant validoAte = conversa.linksValidosAte();
        return new LeituraView(nomeDoArquivo, c.chamado(), c.contato(), c.inicio(), c.fim(), c.assunto(),
                (int) conversa.mensagens(), (int) conversa.eventos(), conversa.anexos().size(), porCategoria, validoAte,
                ResumoView.vencido(validoAte, agora), mascarado.omitidos(), conversa.linhasSemCabecalho());
    }

    private static AtendimentoView.Anexo anexo(ArquivoDoAtendimento a, Instant agora) {
        return new AtendimentoView.Anexo(a.id(), a.ordem(), a.nome(), a.categoria().codigo(), a.situacao(), a.urlValidaAte(),
                "aguardando".equals(a.situacao()) && ResumoView.vencido(a.urlValidaAte(), agora), a.mensagemOrdem(),
                a.momento());
    }

    private void apagarPastaSemFalhar(UUID id) {
        try {
            pasta.apagar(id);
        } catch (RuntimeException e) {
            log.warn("Não foi possível apagar a pasta do atendimento {} que não foi criado: {}", id, e.toString());
        }
    }

    private static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO",
                "Atendimento não encontrado. Ele pode ter sido apagado.");
    }
}
