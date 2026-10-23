package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.AcessoDaPessoaView;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.PerfilView;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarAcessosUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private final AcessosEmMemoria acessos = new AcessosEmMemoria();
    private final CatalogoDePermissoes catalogo = AcessosEmMemoria.catalogo();
    private final UsuarioRepositoryEmMemoria usuarios = UsuarioRepositoryEmMemoria.comTitular().ligadoA(acessos, catalogo);
    private final HorarioTrabalhoRepositoryEmMemoria horarios = new HorarioTrabalhoRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(data -> false), horarios);
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 10, 19, 10, 0, 0, 0, SP).toInstant(), SP);
    private final GerenciarCicloBancoUseCase gerenciarCiclos = new GerenciarCicloBancoUseCase(ciclos,
            new br.com.conferenciaponto.application.ConsolidacaoBancoHoras(new RegistroJornadaRepositoryEmMemoria(),
                    new LancamentoBancoRepositoryEmMemoria()),
            new br.com.conferenciaponto.application.ParametrosBancoHoras(LocalDate.of(2025, 11, 25), 6), eventos::add,
            clock);
    private final PreparacaoDoTitular preparacao = new PreparacaoDoTitular(horarios, regras, gerenciarCiclos, clock);
    private final GerenciarAcessosUseCase useCase = new GerenciarAcessosUseCase(acessos, usuarios, catalogo, preparacao,
            eventos::add, clock);

    {
        acessos.definirPerfis(USUARIO, List.of(acessos.idDo(Perfil.ROLE_ADMIN)), "teste", Instant.EPOCH);
    }

    private Usuario admin() {
        return usuarios.buscarPorId(USUARIO).orElseThrow();
    }

    private Usuario pessoa(String login, String nome, Perfil... perfis) {
        Usuario u = new Usuario(UUID.randomUUID(), login, nome, "h", true, Acesso.NENHUM, null);
        usuarios.salvar(u);
        acessos.definirPerfis(u.id(), java.util.Arrays.stream(perfis).map(acessos::idDo).toList(), "teste", Instant.EPOCH);
        return usuarios.buscarPorId(u.id()).orElseThrow();
    }

    private Usuario relida(Usuario u) {
        return usuarios.buscarPorId(u.id()).orElseThrow();
    }

    private PerfilView perfilChamado(String nome) {
        return useCase.perfis().stream().filter(p -> p.nome().equals(nome)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Os três perfis do sistema: o Administrador tem tudo, inclusive as permissões dos módulos")
    void perfisDoSistema() {
        pessoa("carla", "Carla", Perfil.ROLE_VIEWER);

        assertThat(useCase.perfis()).extracting(PerfilView::nome).containsExactly("Administrador", "Coordenador", "Usuário");
        PerfilView administrador = perfilChamado("Administrador");
        assertThat(administrador.todas()).isTrue();
        assertThat(administrador.doSistema()).isTrue();
        assertThat(administrador.permissoes()).containsAll(catalogo.codigos());
        assertThat(administrador.pessoas()).extracting(GerenciarAcessosUseCase.PessoaDoPerfil::login).containsExactly("eduardo");
        assertThat(perfilChamado("Coordenador").permissoes())
                .containsExactly(PermissoesDoPonto.EQUIPE, PermissoesDoPonto.CONFERIR);
        assertThat(admin().pode("teste.editar")).isTrue();
    }

    @Test
    @DisplayName("Vários perfis se somam: coordenadora e usuária tem o próprio ponto, lança nele e vê o da equipe")
    void variosPerfisSeSomam() {
        Usuario carla = pessoa("carla", "Carla", Perfil.ROLE_VIEWER);
        assertThat(carla.isTitular()).isFalse();
        assertThat(horarios.listarPorUsuario(carla.id())).isEmpty();

        useCase.definirAcesso(admin(), carla.id(), List.of(acessos.idDo(Perfil.ROLE_VIEWER), acessos.idDo(Perfil.ROLE_USER)),
                List.of());

        Usuario agora = relida(carla);
        assertThat(agora.isTitular()).isTrue();
        assertThat(agora.podeEscrever()).isTrue();
        assertThat(agora.podeVerTodos()).isTrue();
        assertThat(agora.acesso().perfis()).containsExactly("Coordenador", "Usuário");
        assertThat(horarios.listarPorUsuario(carla.id())).as("ganhou o próprio ponto: horário padrão").hasSize(1);
        assertThat(ciclos.buscarAberto(carla.id())).isPresent();
        assertThat(eventos).filteredOn(UsuarioAlteradoEvento.class::isInstance).last()
                .satisfies(e -> assertThat(((UsuarioAlteradoEvento) e).usuarioId()).isEqualTo(carla.id()));
        assertThat(acessos.historico).last().satisfies(m -> {
            assertThat(m.usuarioId()).isEqualTo(carla.id());
            assertThat(m.descricao()).isEqualTo("Acesso de Carla: ganhou o perfil Usuário.");
        });
    }

    @Test
    @DisplayName("Ajustes só da pessoa: liberar a mais, retirar o que o perfil dá; o que falta o requisito não vale")
    void ajustesPorPessoa() {
        Usuario maria = pessoa("maria", "Maria", Perfil.ROLE_USER);

        AcessoDaPessoaView view = useCase.definirAcesso(admin(), maria.id(), List.of(acessos.idDo(Perfil.ROLE_USER)), List.of(
                new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false),
                new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, true),
                new AjusteDeAcesso("teste.editar", true)));

        Usuario agora = relida(maria);
        assertThat(agora.isTitular()).isTrue();
        assertThat(agora.podeEscrever()).as("retirado só dela").isFalse();
        assertThat(agora.pode(PermissoesDaAdministracao.LOGS)).isTrue();
        assertThat(agora.pode("teste.editar")).as("sem teste.ler, editar não vale").isFalse();

        assertThat(view.permissoes()).filteredOn(o -> o.permissao().equals(PermissoesDoPonto.LANCAR)).singleElement()
                .satisfies(o -> {
                    assertThat(o.pode()).isFalse();
                    assertThat(o.perfis()).containsExactly("Usuário");
                    assertThat(o.ajuste()).isFalse();
                });
        assertThat(view.permissoes()).filteredOn(o -> o.permissao().equals("teste.editar")).singleElement()
                .satisfies(o -> {
                    assertThat(o.pode()).isFalse();
                    assertThat(o.faltando()).isEqualTo("teste.ler");
                });
        assertThat(acessos.historico).last().satisfies(m -> assertThat(m.descricao())
                .contains("liberado a mais: Editar no módulo, Logs do sistema").contains("retirado: Lançar no próprio ponto"));

        useCase.definirAcesso(admin(), maria.id(), List.of(acessos.idDo(Perfil.ROLE_USER)), List.of());
        assertThat(relida(maria).podeEscrever()).isTrue();
        assertThat(acessos.historico).last().satisfies(m -> assertThat(m.descricao()).contains("voltou a seguir os perfis"));
    }

    @Test
    @DisplayName("Sempre sobra quem cuida dos acessos, e ninguém tira essa permissão de si mesmo")
    void sempreSobraQuemCuida() {
        assertThatThrownBy(() -> useCase.definirAcesso(admin(), USUARIO, List.of(acessos.idDo(Perfil.ROLE_USER)), List.of()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("ninguém ficaria");
        assertThatThrownBy(() -> useCase.definirAcesso(admin(), USUARIO, List.of(acessos.idDo(Perfil.ROLE_ADMIN)),
                List.of(new AjusteDeAcesso(PermissoesDaAdministracao.ACESSOS, false))))
                .isInstanceOf(RegraNegocioException.class);

        Usuario ana = pessoa("ana", "Ana", Perfil.ROLE_ADMIN);
        assertThatThrownBy(() -> useCase.definirAcesso(admin(), USUARIO, List.of(acessos.idDo(Perfil.ROLE_USER)), List.of()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("tiraria de si mesmo");
        useCase.definirAcesso(ana, USUARIO, List.of(acessos.idDo(Perfil.ROLE_USER)), List.of());
        assertThat(relida(admin()).pode(PermissoesDaAdministracao.ACESSOS)).isFalse();
        assertThat(relida(admin()).podeEscrever()).isTrue();
    }

    @Test
    @DisplayName("Perfil novo: o requisito entra junto; nome repetido, curto ou permissão que não existe são recusados")
    void criarPerfil() {
        PerfilView editor = useCase.criarPerfil(admin(), "  Editor   do módulo ", "Edita o módulo", List.of("teste.editar"));

        assertThat(editor.nome()).isEqualTo("Editor do módulo");
        assertThat(editor.permissoes()).containsExactly("teste.ler", "teste.editar");
        assertThat(editor.doSistema()).isFalse();
        assertThat(acessos.historico).last().satisfies(m -> {
            assertThat(m.perfilId()).isEqualTo(editor.id());
            assertThat(m.descricao()).isEqualTo("Perfil Editor do módulo criado: Ler no módulo, Editar no módulo.");
        });

        assertThatThrownBy(() -> useCase.criarPerfil(admin(), "editor DO módulo", null, List.of()))
                .isInstanceOf(ConflitoException.class);
        assertThatThrownBy(() -> useCase.criarPerfil(admin(), "x", null, List.of())).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> useCase.criarPerfil(admin(), "Outro", null, List.of("nao.existe")))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("nao.existe");
        assertThatThrownBy(() -> useCase.definirAcesso(admin(), USUARIO, List.of(UUID.randomUUID()), List.of()))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    @DisplayName("Mudar um perfil vale para todos que o têm; o Administrador não muda; os do sistema não são excluídos")
    void alterarEExcluir() {
        Usuario maria = pessoa("maria", "Maria", Perfil.ROLE_USER);
        Usuario joao = pessoa("joao", "João", Perfil.ROLE_USER);
        UUID usuario = acessos.idDo(Perfil.ROLE_USER);
        eventos.clear();

        PerfilView alterado = useCase.alterarPerfil(admin(), usuario, "Usuário", "Registra o próprio ponto",
                List.of(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR, "teste.ler"));

        assertThat(alterado.pessoas()).extracting(GerenciarAcessosUseCase.PessoaDoPerfil::login).containsExactly("joao", "maria");
        assertThat(relida(maria).pode("teste.ler")).isTrue();
        assertThat(relida(joao).pode("teste.ler")).isTrue();
        assertThat(eventos).filteredOn(UsuarioAlteradoEvento.class::isInstance).hasSize(2);
        assertThat(acessos.historico).last().satisfies(m -> assertThat(m.descricao())
                .isEqualTo("Perfil Usuário: passou a liberar Ler no módulo; descrição alterada."));

        assertThatThrownBy(() -> useCase.alterarPerfil(admin(), acessos.idDo(Perfil.ROLE_ADMIN), "Administrador", null, List.of()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("não muda");
        assertThatThrownBy(() -> useCase.excluirPerfil(admin(), usuario))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("do sistema");

        PerfilView atendente = useCase.criarPerfil(admin(), "Atendente", null, List.of("teste.ler"));
        useCase.definirAcesso(admin(), maria.id(), List.of(usuario, atendente.id()), List.of());
        useCase.excluirPerfil(admin(), atendente.id());
        assertThat(acessos.perfisDo(maria.id())).containsExactly(usuario);
        assertThat(acessos.historico).last().satisfies(m -> assertThat(m.descricao())
                .isEqualTo("Perfil Atendente excluído; saiu de Maria."));
    }

    @Test
    @DisplayName("Tirar do Administrador quem cuida dos acessos (excluindo ou mudando outro perfil) também é conferido")
    void naoDeixaSemQuemCuidaPorPerfil() {
        PerfilView gestor = useCase.criarPerfil(admin(), "Gestor", null, List.of(PermissoesDaAdministracao.ACESSOS));
        Usuario ana = pessoa("ana", "Ana");
        useCase.definirAcesso(admin(), ana.id(), List.of(gestor.id()), List.of());
        useCase.definirAcesso(relida(ana), USUARIO, List.of(acessos.idDo(Perfil.ROLE_USER)), List.of());

        assertThatThrownBy(() -> useCase.excluirPerfil(relida(ana), gestor.id()))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> useCase.alterarPerfil(relida(ana), gestor.id(), "Gestor", null, List.of(PermissoesDaAdministracao.LOGS)))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("O histórico vem do mais novo para o mais antigo, com o nome da pessoa")
    void historico() {
        Usuario maria = pessoa("maria", "Maria Souza", Perfil.ROLE_USER);
        useCase.definirAcesso(admin(), maria.id(), List.of(acessos.idDo(Perfil.ROLE_VIEWER)), List.of());
        useCase.criarPerfil(admin(), "Atendente", null, List.of());

        assertThat(useCase.historico(null, 10)).extracting(GerenciarAcessosUseCase.MudancaView::descricao)
                .containsExactly("Perfil Atendente criado, ainda sem nada liberado.",
                        "Acesso de Maria Souza: ganhou o perfil Coordenador; perdeu o perfil Usuário.");
        assertThat(useCase.historico(maria.id(), 10)).singleElement()
                .satisfies(m -> assertThat(m.pessoa()).isEqualTo("Maria Souza"));
    }
}
