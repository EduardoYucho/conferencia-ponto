package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.TextoDuracao;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Avisa que o banco de horas está perto do fechamento previsto: 30 e 15 dias antes e depois que a
 * previsão passa. Roda todo dia às 01:00 e na subida da aplicação (o PC pode estar desligado de
 * madrugada): quem perdeu o dia exato recebe o aviso da faixa em que está. Cada aviso sai uma vez por
 * ciclo/previsão.
 */
@Service
public class VerificarPrazoCicloUseCase {

    public static final int AVISO_30_DIAS = 30;
    public static final int AVISO_15_DIAS = 15;

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GerenciarCicloBancoUseCase ciclos;
    private final NotificacoesUseCase notificacoes;

    public VerificarPrazoCicloUseCase(GerenciarCicloBancoUseCase ciclos, NotificacoesUseCase notificacoes) {
        this.ciclos = ciclos;
        this.notificacoes = notificacoes;
    }

    @Transactional
    public Optional<Notificacao> executar(LocalDate hoje) {
        CicloBancoView view = ciclos.atual();
        CicloBanco ciclo = view.ciclo();
        notificacoes.arquivarAvisosDeCicloExceto(":%s:%s".formatted(ciclo.id(), ciclo.dataFimPrevista()));
        long dias = ciclo.diasAtePrevisao(hoje);
        String previsao = DATA.format(ciclo.dataFimPrevista());
        String saldo = TextoDuracao.saldo(view.saldoSegundos());

        TipoNotificacao tipo;
        String titulo;
        String mensagem;
        if (dias < 0) {
            tipo = TipoNotificacao.CICLO_VENCIDO;
            titulo = "Fechamento do banco de horas pendente";
            mensagem = "A previsão de fechamento era %s e o ciclo continua aberto (saldo %s). Quando o RH zerar o banco, clique em \"Fechar banco de horas\"."
                    .formatted(previsao, saldo);
        } else if (dias <= AVISO_15_DIAS) {
            tipo = TipoNotificacao.CICLO_15_DIAS;
            titulo = dias == 0 ? "Banco de horas fecha hoje" : "Faltam %d dias para o fechamento do banco".formatted(dias);
            mensagem = "O ciclo atual está previsto para fechar em %s. Saldo do ciclo: %s.".formatted(previsao, saldo);
        } else if (dias <= AVISO_30_DIAS) {
            tipo = TipoNotificacao.CICLO_30_DIAS;
            titulo = "Faltam %d dias para o fechamento do banco".formatted(dias);
            mensagem = "O ciclo atual está previsto para fechar em %s. Saldo do ciclo: %s.".formatted(previsao, saldo);
        } else {
            return Optional.empty();
        }
        String chave = "%s:%s:%s".formatted(tipo, ciclo.id(), ciclo.dataFimPrevista());
        return notificacoes.notificar(tipo, chave, titulo, mensagem, "/");
    }
}
