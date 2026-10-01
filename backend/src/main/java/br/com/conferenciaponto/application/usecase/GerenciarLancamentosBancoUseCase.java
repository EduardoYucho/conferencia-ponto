package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.TextoDuracao;
import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Lançamentos avulsos no banco de horas: abater horas (ex.: compensar com uma folga ou saída antecipada,
 * horas pagas pela empresa) ou creditar (correção do RH). Só valem no ciclo aberto: o saldo de um ciclo
 * fechado já foi congelado.
 */
@Service
public class GerenciarLancamentosBancoUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final LancamentoBancoRepository lancamentos;
    private final CicloBancoRepository ciclos;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarLancamentosBancoUseCase(LancamentoBancoRepository lancamentos, CicloBancoRepository ciclos,
                                            ApplicationEventPublisher eventos, Clock clock) {
        this.lancamentos = lancamentos;
        this.ciclos = ciclos;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<LancamentoBanco> listar(LocalDate inicio, LocalDate fim) {
        return lancamentos.listarNoPeriodo(inicio, fim);
    }

    /**
     * @param segundos negativo abate do banco; positivo credita
     */
    @Transactional
    public LancamentoBanco lancar(LocalDate data, int segundos, String descricao, String usuario) {
        LancamentoBanco novo = LancamentoBanco.novo(data, segundos, descricao, usuario, clock.instant());
        exigirCicloAberto(data);
        LocalDate limite = LocalDate.now(clock).plusYears(1);
        if (data.isAfter(limite)) {
            throw new RegraNegocioException("LANCAMENTO_DISTANTE", "A data do lançamento pode ser no máximo um ano à frente.");
        }
        lancamentos.salvar(novo);
        eventos.publishEvent(new LancamentoBancoAlteradoEvento(data, "%s %s no banco em %s · %s".formatted(
                novo.isDebito() ? "Abatido" : "Creditado", TextoDuracao.duracao(segundos), DATA.format(data),
                novo.descricao())));
        return novo;
    }

    @Transactional
    public void excluir(UUID id) {
        LancamentoBanco lancamento = lancamentos.buscarPorId(id).orElseThrow(() -> new RecursoNaoEncontradoException(
                "LANCAMENTO_NAO_ENCONTRADO", "Lançamento no banco de horas não encontrado."));
        exigirCicloAberto(lancamento.data());
        lancamentos.excluir(id);
        eventos.publishEvent(new LancamentoBancoAlteradoEvento(lancamento.data(), "Lançamento de %s removido (%s)"
                .formatted(DATA.format(lancamento.data()), TextoDuracao.saldo(lancamento.segundos()))));
    }

    private void exigirCicloAberto(LocalDate data) {
        CicloBanco aberto = ciclos.buscarAberto().orElse(null);
        if (aberto != null && data.isBefore(aberto.dataInicio())) {
            throw new RegraNegocioException("LANCAMENTO_EM_CICLO_FECHADO",
                    "O banco de horas foi fechado e o ciclo atual começou em %s: lance a partir dessa data."
                            .formatted(DATA.format(aberto.dataInicio())));
        }
    }
}
