package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.RelatorioRhRecebidoEvento;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRhLido;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.port.LeitorRelatorioRh;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Recebe o PDF do relatório de banco de horas do RH: lê, grava os dias (sem CPF e sem guardar o arquivo)
 * e dispara a conferência com os dias locais em segundo plano. Nada da conferência é alterado aqui.
 */
@Service
public class ImportarRelatorioRhUseCase {

    public static final int TAMANHO_MAXIMO = 10 * 1024 * 1024;
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LeitorRelatorioRh leitor;
    private final RelatorioRhRepository relatorios;
    private final ConferirConciliacaoUseCase conferir;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ImportarRelatorioRhUseCase(LeitorRelatorioRh leitor, RelatorioRhRepository relatorios,
                                      ConferirConciliacaoUseCase conferir, ApplicationEventPublisher eventos,
                                      Clock clock) {
        this.leitor = leitor;
        this.relatorios = relatorios;
        this.conferir = conferir;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public RelatorioRh receber(String nomeArquivo, byte[] pdf, String usuario) {
        if (pdf == null || pdf.length == 0) {
            throw new RegraNegocioException("RELATORIO_VAZIO", "Selecione o PDF do relatório de banco de horas.");
        }
        if (pdf.length > TAMANHO_MAXIMO) {
            throw new RegraNegocioException("RELATORIO_GRANDE_DEMAIS", "O arquivo passa de 10 MB.");
        }
        String hash = sha256(pdf);
        relatorios.buscarPorHash(hash).ifPresent(existente -> {
            throw new ConflitoException("RELATORIO_JA_ENVIADO",
                    "Este relatório (período %s a %s) já foi enviado em %s.".formatted(
                            DATA.format(existente.periodoInicio()), DATA.format(existente.periodoFim()),
                            DATA_HORA.format(existente.enviadoEm().atZone(zona()))));
        });

        RelatorioRhLido lido = leitor.ler(pdf);
        LocalDate diaDaEmissao = lido.emitidoEm().toLocalDate();
        List<DiaRelatorioRh> dias = lido.dias().stream()
                .filter(d -> !d.data().isBefore(lido.periodoInicio()) && !d.data().isAfter(lido.periodoFim()))
                .filter(d -> d.data().isBefore(diaDaEmissao)) // o dia da emissão ainda estava em andamento
                .toList();
        if (dias.isEmpty()) {
            throw new RegraNegocioException("RELATORIO_SEM_DIAS",
                    "O relatório não tem dias anteriores à emissão (%s) para conferir."
                            .formatted(DATA.format(diaDaEmissao)));
        }
        String nome = nomeArquivo == null || nomeArquivo.isBlank() ? "relatorio-rh.pdf" : nomeArquivo.strip();
        RelatorioRh relatorio = new RelatorioRh(UUID.randomUUID(), nome.length() > 255 ? nome.substring(0, 255) : nome,
                hash, lido.funcionario(), lido.emitidoEm(), lido.periodoInicio(), lido.periodoFim(),
                lido.totalPrevistoSegundos(), lido.totalTrabalhadoSegundos(), lido.totalSaldoSegundos(), dias.size(),
                StatusRelatorioRh.PROCESSANDO, null, 0, clock.instant(), usuario, null);
        relatorios.salvar(relatorio, dias);
        eventos.publishEvent(new RelatorioRhRecebidoEvento(relatorio.id()));
        return relatorio;
    }

    /**
     * Remove um relatório enviado por engano. As divergências dele somem e o período é reconferido com
     * os relatórios que sobraram (o que foi aceito do RH continua na conferência).
     */
    @Transactional
    public void excluir(UUID id) {
        RelatorioRh relatorio = relatorios.buscarPorId(id).orElseThrow(() ->
                new RecursoNaoEncontradoException("RELATORIO_NAO_ENCONTRADO", "Relatório do RH não encontrado."));
        relatorios.excluir(id);
        conferir.conferir(relatorio.periodoInicio(), relatorio.ultimoDiaConferido(), "Relatório do RH removido");
    }

    private ZoneId zona() {
        return clock.getZone();
    }

    static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
