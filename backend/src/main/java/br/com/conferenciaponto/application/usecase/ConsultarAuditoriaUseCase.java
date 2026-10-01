package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.application.view.ComprovanteArquivoView;
import br.com.conferenciaponto.application.view.MesJornadaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Dados da tela de auditoria (coordenação): dias do mês + comprovantes, em uma única consulta por tabela. */
@Service
@Transactional(readOnly = true)
public class ConsultarAuditoriaUseCase {

    private final ConsultarJornadaUseCase consultarJornada;
    private final ComprovanteArquivadoRepository arquivos;
    private final ArmazenamentoComprovantes armazenamento;
    private final AjusteJornadaRepository ajustes;

    public ConsultarAuditoriaUseCase(ConsultarJornadaUseCase consultarJornada, ComprovanteArquivadoRepository arquivos,
                                     ArmazenamentoComprovantes armazenamento, AjusteJornadaRepository ajustes) {
        this.consultarJornada = consultarJornada;
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.ajustes = ajustes;
    }

    public AuditoriaMesView mes(UUID usuarioId, YearMonth referencia) {
        MesJornadaView mes = consultarJornada.mes(usuarioId, referencia);
        List<UUID> ids = mes.dias().stream().map(RegistroJornadaView::id).toList();

        Map<UUID, List<ComprovanteArquivoView>> porRegistro = arquivos.listarPorRegistros(ids).stream()
                .collect(Collectors.groupingBy(ComprovanteArquivado::registroJornadaId,
                        Collectors.mapping(c -> new ComprovanteArquivoView(c, armazenamento.uriDeAcesso(c.id())),
                                Collectors.toList())));

        Map<LocalDate, List<AjusteJornada>> ajustesPorData = ajustes
                .listarPorPeriodo(usuarioId, referencia.atDay(1), referencia.atEndOfMonth()).stream()
                .collect(Collectors.groupingBy(AjusteJornada::data));
        List<AuditoriaMesView.Dia> dias = mes.dias().stream()
                .map(d -> new AuditoriaMesView.Dia(d, porRegistro.getOrDefault(d.id(), List.of()),
                        ajustesPorData.getOrDefault(d.data(), List.of())))
                .toList();
        return new AuditoriaMesView(referencia, dias, mes.resumo(), mes.ausencias(), mes.lancamentos());
    }
}
