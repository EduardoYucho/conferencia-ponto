package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.evento.PlanilhaAtualizadaEvento;
import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.MontadorPlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import br.com.conferenciaponto.application.planilha.PlanilhasRemotas;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase.Resultado;
import br.com.conferenciaponto.application.view.EstadoPlanilhaView;
import br.com.conferenciaponto.application.view.EstadoPlanilhaView.Situacao;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.model.VinculoPlanilha;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static br.com.conferenciaponto.application.usecase.Fixtures.OUTRO;
import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarPlanilhaUseCaseTest {

    private static final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    private static final String LINK = "https://docs.google.com/spreadsheets/d/" + ID + "/edit?gid=0#gid=0";
    private static final Instant AGORA = Instant.parse("2026-10-02T17:30:00Z");

    /** Serviço de planilhas de mentira: guarda o que foi publicado e falha quando mandado. */
    private static final class Remotas implements PlanilhasRemotas {
        Conta conta = new Conta("planilhas@projeto.iam.gserviceaccount.com", "projeto");
        PlanilhaRemotaException falha;
        final List<Set<Integer>> publicadas = new ArrayList<>();

        @Override
        public Optional<Conta> conta() {
            return Optional.ofNullable(conta);
        }

        @Override
        public Conta configurar(String chaveJson) {
            if (falha != null) {
                throw falha;
            }
            conta = new Conta("nova@projeto.iam.gserviceaccount.com", "projeto");
            return conta;
        }

        @Override
        public void desconfigurar() {
            conta = null;
        }

        @Override
        public String verificar(String planilhaId) {
            if (falha != null) {
                throw falha;
            }
            return "Ponto do Eduardo";
        }

        @Override
        public String publicar(String planilhaId, PlanilhaConferencia planilha, Set<Integer> abas) {
            if (falha != null) {
                throw falha;
            }
            publicadas.add(abas);
            return "Ponto do Eduardo";
        }
    }

    private final VinculoPlanilhaRepositoryEmMemoria vinculos = new VinculoPlanilhaRepositoryEmMemoria();
    private final Remotas remotas = new Remotas();
    private final List<Object> eventos = new ArrayList<>();
    private final GerenciarPlanilhaUseCase useCase = useCase();

    private GerenciarPlanilhaUseCase useCase() {
        Clock clock = Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo"));
        var registros = new RegistroJornadaRepositoryEmMemoria();
        var lancamentos = new LancamentoBancoRepositoryEmMemoria();
        var ausencias = new AusenciaRepositoryEmMemoria();
        var feriados = new CalendarioFeriadosEmMemoria();
        var consolidacao = new ConsolidacaoBancoHoras(registros, lancamentos);
        var regras = Fixtures.regras(new ClassificadorDiaService(feriados, ausencias));
        var consultar = new ConsultarJornadaUseCase(registros, regras, ausencias, feriados, lancamentos, consolidacao);
        var auditoria = new ConsultarAuditoriaUseCase(consultar, new ComprovanteArquivadoRepositoryEmMemoria(),
                new ArmazenamentoEmMemoria(), new AjusteJornadaRepositoryEmMemoria());
        var ciclo = new GerenciarCicloBancoUseCase(new CicloBancoRepositoryEmMemoria(), consolidacao,
                new ParametrosBancoHoras(LocalDate.of(2026, 5, 25), 6), e -> { }, clock);
        var usuarios = UsuarioRepositoryEmMemoria.comTitular();
        usuarios.salvar(new Usuario(OUTRO, "maria", "Maria", "h:senha", true, Set.of(Perfil.ROLE_USER), null));
        var montador = new MontadorPlanilhaConferencia(usuarios, consolidacao, auditoria, ciclo, regras, clock);
        return new GerenciarPlanilhaUseCase(vinculos, remotas, montador, eventos::add, clock);
    }

    private static PlanilhaRemotaException semPermissao() {
        return new PlanilhaRemotaException("GOOGLE_SEM_PERMISSAO", "A planilha não está compartilhada.", false);
    }

    private static PlanilhaRemotaException semConexao() {
        return new PlanilhaRemotaException("GOOGLE_SEM_CONEXAO", "Sem conexão com o Google.", true);
    }

    @Test
    @DisplayName("O link colado da barra de endereços vira o identificador da planilha")
    void idDoLink() {
        assertThat(VinculoPlanilha.idDoLink(LINK)).isEqualTo(ID);
        assertThat(VinculoPlanilha.idDoLink("  docs.google.com/spreadsheets/d/" + ID + "  ")).isEqualTo(ID);
        assertThat(VinculoPlanilha.idDoLink(ID)).isEqualTo(ID);
        for (String invalido : new String[]{null, "", "https://docs.google.com/document/d/" + ID, "minha planilha"}) {
            assertThatThrownBy(() -> VinculoPlanilha.idDoLink(invalido)).isInstanceOf(RegraNegocioException.class)
                    .hasMessageContaining("link da planilha");
        }
        assertThat(VinculoPlanilha.novo(USUARIO, ID, "x", "eduardo", AGORA).url())
                .isEqualTo("https://docs.google.com/spreadsheets/d/" + ID + "/edit");
    }

    @Test
    @DisplayName("Vincular grava a planilha inteira na hora e só então guarda o vínculo")
    void vincular() {
        assertThat(useCase.estado(USUARIO).situacao()).isEqualTo(Situacao.SEM_PLANILHA);

        EstadoPlanilhaView estado = useCase.vincular(USUARIO, LINK, "eduardo");

        assertThat(estado.situacao()).isEqualTo(Situacao.SINCRONIZADA);
        assertThat(estado.emailServico()).isEqualTo("planilhas@projeto.iam.gserviceaccount.com");
        assertThat(estado.vinculo().planilhaId()).isEqualTo(ID);
        assertThat(estado.vinculo().titulo()).isEqualTo("Ponto do Eduardo");
        assertThat(estado.vinculo().sincronizadaEm()).isEqualTo(AGORA);
        assertThat(estado.vinculo().vinculadaPor()).isEqualTo("eduardo");
        assertThat(remotas.publicadas).as("todas as abas").containsExactly((Set<Integer>) null);
        assertThat(useCase.vinculados()).containsExactly(USUARIO);
        assertThat(eventos).singleElement().isInstanceOfSatisfying(PlanilhaAtualizadaEvento.class,
                e -> assertThat(e.usuarioId()).isEqualTo(USUARIO));
    }

    @Test
    @DisplayName("Planilha não compartilhada, de outra pessoa ou sem a integração: não vincula e diz por quê")
    void vincularRecusado() {
        remotas.falha = semPermissao();
        assertThatThrownBy(() -> useCase.vincular(USUARIO, LINK, "eduardo"))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_SEM_PERMISSAO"));
        assertThat(vinculos.listar()).isEmpty();

        remotas.falha = null;
        useCase.vincular(OUTRO, LINK, "maria");
        assertThatThrownBy(() -> useCase.vincular(USUARIO, LINK, "eduardo"))
                .isInstanceOfSatisfying(ConflitoException.class, e -> assertThat(e.getCodigo()).isEqualTo("PLANILHA_EM_USO"));
        // a própria pessoa pode vincular de novo a mesma planilha
        assertThat(useCase.vincular(OUTRO, LINK, "maria").situacao()).isEqualTo(Situacao.SINCRONIZADA);

        remotas.conta = null;
        assertThatThrownBy(() -> useCase.vincular(USUARIO, "https://docs.google.com/spreadsheets/d/" + ID + "x", "eduardo"))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo("PLANILHA_SEM_INTEGRACAO"));
        assertThat(useCase.estado(USUARIO).situacao()).isEqualTo(Situacao.SEM_INTEGRACAO);
    }

    @Test
    @DisplayName("Gravação automática: só os meses que mudaram e o resumo; depois de uma falha, tudo de novo")
    void sincronizar() {
        assertThat(useCase.sincronizar(USUARIO, null)).isEqualTo(Resultado.SEM_PLANILHA);
        useCase.vincular(USUARIO, LINK, "eduardo");
        remotas.publicadas.clear();

        assertThat(useCase.sincronizar(USUARIO, Set.of(YearMonth.of(2026, 6)))).isEqualTo(Resultado.GRAVADA);
        assertThat(useCase.sincronizar(USUARIO, Set.of())).isEqualTo(Resultado.GRAVADA);
        assertThat(remotas.publicadas).containsExactly(Set.of(Aba.ID_RESUMO, 202606), Set.of(Aba.ID_RESUMO));

        remotas.falha = semConexao();
        assertThat(useCase.sincronizar(USUARIO, Set.of())).isEqualTo(Resultado.TENTAR_DE_NOVO);
        EstadoPlanilhaView comErro = useCase.estado(USUARIO);
        assertThat(comErro.situacao()).isEqualTo(Situacao.ERRO);
        assertThat(comErro.vinculo().erro()).isEqualTo("Sem conexão com o Google.");
        assertThat(comErro.vinculo().sincronizadaEm()).as("a data da última gravação boa fica").isEqualTo(AGORA);

        remotas.falha = semPermissao();
        assertThat(useCase.sincronizar(USUARIO, Set.of())).isEqualTo(Resultado.FALHOU);

        remotas.falha = null;
        remotas.publicadas.clear();
        assertThat(useCase.sincronizar(USUARIO, Set.of(YearMonth.of(2026, 6)))).isEqualTo(Resultado.GRAVADA);
        assertThat(remotas.publicadas).as("depois de falhar, regrava todas as abas").containsExactly((Set<Integer>) null);
        assertThat(useCase.estado(USUARIO).situacao()).isEqualTo(Situacao.SINCRONIZADA);

        remotas.conta = null;
        assertThat(useCase.sincronizar(USUARIO, null)).isEqualTo(Resultado.SEM_PLANILHA);
    }

    @Test
    @DisplayName("Atualizar agora devolve o erro para a tela; desconectar deixa de gravar")
    void atualizarAgoraEDesvincular() {
        assertThatThrownBy(() -> useCase.sincronizarAgora(USUARIO))
                .isInstanceOfSatisfying(RegraNegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo("PLANILHA_NAO_VINCULADA"));
        useCase.vincular(USUARIO, LINK, "eduardo");

        remotas.falha = semPermissao();
        assertThatThrownBy(() -> useCase.sincronizarAgora(USUARIO)).isInstanceOf(RegraNegocioException.class)
                .hasMessage("A planilha não está compartilhada.");
        assertThat(useCase.estado(USUARIO).situacao()).isEqualTo(Situacao.ERRO);

        remotas.falha = null;
        assertThat(useCase.sincronizarAgora(USUARIO).situacao()).isEqualTo(Situacao.SINCRONIZADA);

        assertThat(useCase.desvincular(USUARIO).situacao()).isEqualTo(Situacao.SEM_PLANILHA);
        assertThat(useCase.sincronizar(USUARIO, null)).isEqualTo(Resultado.SEM_PLANILHA);
        assertThat(eventos).last().isInstanceOf(PlanilhaAtualizadaEvento.class);
    }

    @Test
    @DisplayName("Integração (administrador): conta configurada, quantas planilhas, chave recusada vira mensagem")
    void integracao() {
        useCase.vincular(USUARIO, LINK, "eduardo");
        assertThat(useCase.integracao().planilhas()).isEqualTo(1);
        assertThat(useCase.configurar("{...}").conta().email()).isEqualTo("nova@projeto.iam.gserviceaccount.com");

        remotas.falha = new PlanilhaRemotaException("GOOGLE_CHAVE_INVALIDA", "O arquivo não é um JSON.", false);
        assertThatThrownBy(() -> useCase.configurar("x")).isInstanceOf(RegraNegocioException.class)
                .hasMessage("O arquivo não é um JSON.");

        assertThat(useCase.desconfigurar().conta()).isNull();
        assertThat(useCase.estado(USUARIO).situacao()).as("a planilha continua vinculada, mas parada")
                .isEqualTo(Situacao.SEM_INTEGRACAO);
    }
}
