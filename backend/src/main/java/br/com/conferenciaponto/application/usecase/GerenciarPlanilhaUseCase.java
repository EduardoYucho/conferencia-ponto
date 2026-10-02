package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.PlanilhaAtualizadaEvento;
import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.MontadorPlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import br.com.conferenciaponto.application.planilha.PlanilhasRemotas;
import br.com.conferenciaponto.application.view.EstadoPlanilhaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.VinculoPlanilha;
import br.com.conferenciaponto.domain.port.VinculoPlanilhaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Planilha de conferência: o arquivo Excel (montado na hora) e a planilha do Google Sheets de cada usuário,
 * reescrita a cada mudança no ponto dele.
 *
 * <p>Sem transação de banco em volta: gravar no Google leva segundos e não pode segurar uma conexão. A
 * montagem dos dados abre a própria transação de leitura.
 */
@Service
public class GerenciarPlanilhaUseCase {

    /** Resultado de uma gravação em segundo plano. */
    public enum Resultado {
        GRAVADA,
        /** Sem planilha vinculada ou sem a conta de serviço: nada a fazer. */
        SEM_PLANILHA,
        /** Falhou por algo passageiro (rede, limite do Google): vale tentar de novo. */
        TENTAR_DE_NOVO,
        /** Falhou e depende de alguém (compartilhar a planilha, ativar a API...). */
        FALHOU
    }

    /**
     * Integração com o Google.
     *
     * @param conta     conta de serviço configurada ({@code null} = integração desligada)
     * @param planilhas quantas pessoas já vincularam a planilha delas
     */
    public record Integracao(PlanilhasRemotas.Conta conta, int planilhas) {
    }

    private static final Logger log = LoggerFactory.getLogger(GerenciarPlanilhaUseCase.class);

    private final VinculoPlanilhaRepository vinculos;
    private final PlanilhasRemotas remotas;
    private final MontadorPlanilhaConferencia montador;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;
    /** Uma gravação por vez em cada planilha (a automática e a pedida pela tela não se atropelam). */
    private final Map<UUID, Object> travas = new ConcurrentHashMap<>();

    public GerenciarPlanilhaUseCase(VinculoPlanilhaRepository vinculos, PlanilhasRemotas remotas,
                                    MontadorPlanilhaConferencia montador, ApplicationEventPublisher eventos,
                                    Clock clock) {
        this.vinculos = vinculos;
        this.remotas = remotas;
        this.montador = montador;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** A planilha de conferência do usuário (para o arquivo Excel). */
    public PlanilhaConferencia montar(UUID usuarioId) {
        return montador.montar(usuarioId);
    }

    public EstadoPlanilhaView estado(UUID usuarioId) {
        return new EstadoPlanilhaView(remotas.conta().map(PlanilhasRemotas.Conta::email).orElse(null),
                vinculos.buscar(usuarioId).orElse(null));
    }

    /** Usuários com planilha vinculada. */
    public List<UUID> vinculados() {
        return vinculos.listar().stream().map(VinculoPlanilha::usuarioId).toList();
    }

    /**
     * Passa a manter a conferência do usuário na planilha do link e já grava tudo nela. Só vincula se a primeira
     * gravação der certo (a planilha existe e está compartilhada com a conta do sistema como Editor).
     */
    public EstadoPlanilhaView vincular(UUID usuarioId, String link, String quem) {
        String planilhaId = VinculoPlanilha.idDoLink(link);
        if (remotas.conta().isEmpty()) {
            throw new RegraNegocioException("PLANILHA_SEM_INTEGRACAO",
                    "O administrador ainda não configurou a integração com o Google Sheets.");
        }
        vinculos.buscarPorPlanilha(planilhaId).filter(outro -> !outro.usuarioId().equals(usuarioId)).ifPresent(outro -> {
            throw new ConflitoException("PLANILHA_EM_USO", "Essa planilha já é a de outra pessoa: cada um usa a sua.");
        });
        synchronized (trava(usuarioId)) {
            try {
                remotas.verificar(planilhaId);
                String titulo = remotas.publicar(planilhaId, montador.montar(usuarioId), null);
                vinculos.salvar(VinculoPlanilha.novo(usuarioId, planilhaId, titulo, quem, clock.instant())
                        .sincronizada(titulo, clock.instant()));
            } catch (PlanilhaRemotaException e) {
                throw new RegraNegocioException(e.getCodigo(), e.getMessage());
            }
        }
        log.info("Planilha do Google vinculada ao usuário {} por {}", usuarioId, quem);
        return avisar(usuarioId);
    }

    /** O sistema deixa de gravar na planilha (ela continua existindo no Google, como está). */
    public EstadoPlanilhaView desvincular(UUID usuarioId) {
        synchronized (trava(usuarioId)) {
            vinculos.excluir(usuarioId);
        }
        return avisar(usuarioId);
    }

    /** Botão "Atualizar agora": regrava todas as abas e devolve o erro, se houver, para a tela. */
    public EstadoPlanilhaView sincronizarAgora(UUID usuarioId) {
        VinculoPlanilha vinculo = vinculos.buscar(usuarioId).orElseThrow(() -> new RegraNegocioException(
                "PLANILHA_NAO_VINCULADA", "Informe primeiro o link da planilha do Google."));
        try {
            publicar(vinculo, null);
        } catch (PlanilhaRemotaException e) {
            throw new RegraNegocioException(e.getCodigo(), e.getMessage());
        }
        return estado(usuarioId);
    }

    /**
     * Gravação automática, em segundo plano.
     *
     * @param meses meses que mudaram (grava só as abas deles e o resumo); {@code null} = todas as abas
     */
    public Resultado sincronizar(UUID usuarioId, Set<YearMonth> meses) {
        Optional<VinculoPlanilha> vinculo = vinculos.buscar(usuarioId);
        if (vinculo.isEmpty() || remotas.conta().isEmpty()) {
            return Resultado.SEM_PLANILHA;
        }
        try {
            publicar(vinculo.get(), meses);
            return Resultado.GRAVADA;
        } catch (PlanilhaRemotaException e) {
            return e.isTransitoria() ? Resultado.TENTAR_DE_NOVO : Resultado.FALHOU;
        }
    }

    /** Configuração do administrador. */
    public Integracao integracao() {
        return new Integracao(remotas.conta().orElse(null), vinculos.listar().size());
    }

    public Integracao configurar(String chaveJson) {
        try {
            remotas.configurar(chaveJson);
        } catch (PlanilhaRemotaException e) {
            throw new RegraNegocioException(e.getCodigo(), e.getMessage());
        }
        return integracao();
    }

    public Integracao desconfigurar() {
        remotas.desconfigurar();
        return integracao();
    }

    private void publicar(VinculoPlanilha vinculo, Set<YearMonth> meses) {
        UUID usuarioId = vinculo.usuarioId();
        synchronized (trava(usuarioId)) {
            // depois de uma falha (ou antes da primeira gravação) as outras abas podem estar desatualizadas
            boolean tudo = meses == null || vinculo.sincronizadaEm() == null || vinculo.erro() != null;
            try {
                String titulo = remotas.publicar(vinculo.planilhaId(), montador.montar(usuarioId), tudo ? null : abas(meses));
                gravar(vinculo.sincronizada(titulo, clock.instant()));
            } catch (PlanilhaRemotaException e) {
                log.warn("Planilha do Google do usuário {} não foi gravada ({}): {}", usuarioId, e.getCodigo(), e.getMessage());
                gravar(vinculo.comErro(e.getMessage()));
                throw e;
            } catch (RuntimeException e) {
                log.error("Erro ao montar a planilha do usuário {}", usuarioId, e);
                gravar(vinculo.comErro("Erro inesperado ao montar a planilha. Consulte os logs do servidor."));
                throw new PlanilhaRemotaException("PLANILHA_ERRO_INTERNO",
                        "Erro inesperado ao montar a planilha. Consulte os logs do servidor.", false, e);
            }
        }
    }

    private static Set<Integer> abas(Set<YearMonth> meses) {
        Set<Integer> ids = new HashSet<>();
        ids.add(Aba.ID_RESUMO);
        meses.forEach(m -> ids.add(m.getYear() * 100 + m.getMonthValue()));
        return ids;
    }

    /** Só grava se o vínculo ainda é o mesmo (a pessoa pode ter trocado de planilha durante a gravação). */
    private void gravar(VinculoPlanilha novo) {
        boolean mesmo = vinculos.buscar(novo.usuarioId())
                .filter(atual -> atual.planilhaId().equals(novo.planilhaId())).isPresent();
        if (mesmo) {
            vinculos.salvar(novo);
            avisar(novo.usuarioId());
        }
    }

    private EstadoPlanilhaView avisar(UUID usuarioId) {
        EstadoPlanilhaView estado = estado(usuarioId);
        eventos.publishEvent(new PlanilhaAtualizadaEvento(usuarioId, estado));
        return estado;
    }

    private Object trava(UUID usuarioId) {
        return travas.computeIfAbsent(usuarioId, id -> new Object());
    }
}
