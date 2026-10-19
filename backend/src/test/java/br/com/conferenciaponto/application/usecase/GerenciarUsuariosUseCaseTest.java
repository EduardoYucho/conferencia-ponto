package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
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

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarUsuariosUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private final AcessosEmMemoria acessos = new AcessosEmMemoria();
    private final CatalogoDePermissoes catalogo = AcessosEmMemoria.catalogo();
    private final UsuarioRepositoryEmMemoria usuarios = UsuarioRepositoryEmMemoria.comTitular().ligadoA(acessos, catalogo);
    private final HorarioTrabalhoRepositoryEmMemoria horarios = new HorarioTrabalhoRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(data -> false), horarios);
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, SP).toInstant(), SP);
    private final CodificadorSenha codificador = new CodificadorSenha() {
        @Override
        public String codificar(String senha) {
            return "h:" + senha;
        }

        @Override
        public boolean confere(String senha, String hash) {
            return ("h:" + senha).equals(hash);
        }
    };
    private final GerenciarCicloBancoUseCase gerenciarCiclos = new GerenciarCicloBancoUseCase(ciclos,
            new br.com.conferenciaponto.application.ConsolidacaoBancoHoras(new RegistroJornadaRepositoryEmMemoria(),
                    new LancamentoBancoRepositoryEmMemoria()),
            new br.com.conferenciaponto.application.ParametrosBancoHoras(LocalDate.of(2025, 11, 25), 6), eventos::add,
            clock);
    private final PreparacaoDoTitular preparacao = new PreparacaoDoTitular(horarios, regras, gerenciarCiclos, clock);
    private final GerenciarAcessosUseCase gerenciarAcessos = new GerenciarAcessosUseCase(acessos, usuarios, catalogo,
            preparacao, eventos::add, clock);
    private final GerenciarUsuariosUseCase useCase = new GerenciarUsuariosUseCase(usuarios, codificador, preparacao,
            gerenciarAcessos, eventos::add);

    {
        acessos.definirPerfis(USUARIO, List.of(acessos.idDo(Perfil.ROLE_ADMIN)), "teste", Instant.EPOCH);
    }

    private Usuario admin() {
        return usuarios.buscarPorId(USUARIO).orElseThrow();
    }

    private List<java.util.UUID> perfil(Perfil perfil) {
        return List.of(acessos.idDo(perfil));
    }

    @Test
    @DisplayName("Cria o acesso com senha provisória e o perfil escolhido; quem tem o próprio ponto ganha horário padrão e ciclo do banco")
    void criaTitular() {
        Usuario maria = useCase.criar(" Maria.Souza ", "Maria Souza", perfil(Perfil.ROLE_USER), "provisoria1",
                "\\\\SERVIDOR\\Ponto\\Maria", admin());

        assertThat(maria.login()).isEqualTo("maria.souza");
        assertThat(maria.trocarSenha()).isTrue();
        assertThat(maria.acesso().perfis()).containsExactly("Usuário");
        assertThat(maria.isTitular()).isTrue();
        assertThat(maria.podeEscrever()).isTrue();
        assertThat(codificador.confere("provisoria1", maria.senhaHash())).isTrue();
        assertThat(maria.pastaComprovantes()).isEqualTo("\\\\SERVIDOR\\Ponto\\Maria");
        assertThat(horarios.listarPorUsuario(maria.id())).singleElement()
                .satisfies(h -> assertThat(h.previstoSegundos(LocalDate.of(2026, 9, 28))).isEqualTo(31_680));
        // ciclo da empresa que contém hoje (25/11/2025 + 6 meses = 25/05/2026)
        assertThat(ciclos.buscarAberto(maria.id())).get()
                .satisfies(c -> assertThat(c.dataInicio()).isEqualTo(LocalDate.of(2026, 5, 25)));
        assertThat(eventos).last().isInstanceOf(UsuarioAlteradoEvento.class);
        assertThat(acessos.historico).last().satisfies(m -> {
            assertThat(m.usuarioId()).isEqualTo(maria.id());
            assertThat(m.quem()).isEqualTo("eduardo");
            assertThat(m.descricao()).contains("Maria Souza").contains("o perfil Usuário");
        });
    }

    @Test
    @DisplayName("Quem só consulta a equipe não tem horário, ciclo nem pasta")
    void criaCoordenacao() {
        Usuario coord = useCase.criar("coordenacao", "Coordenação", perfil(Perfil.ROLE_VIEWER), "provisoria1", null, admin());

        assertThat(coord.podeVerTodos()).isTrue();
        assertThat(coord.isTitular()).isFalse();
        assertThat(horarios.listarPorUsuario(coord.id())).isEmpty();
        assertThat(ciclos.buscarAberto(coord.id())).isEmpty();
        assertThatThrownBy(() -> useCase.definirPasta(coord.id(), "C:/Ponto", admin()))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Sem perfil, a pessoa entra sem acesso a nada; só quem cuida dos acessos escolhe os perfis no cadastro")
    void semPerfil() {
        Usuario cadastrador = useCase.criar("cadastro", "Cadastro", List.of(), "provisoria1", null, admin());
        gerenciarAcessos.definirAcesso(admin(), cadastrador.id(), List.of(),
                List.of(new AjusteDeAcesso(PermissoesDaAdministracao.USUARIOS, true)));
        Usuario quemCadastra = usuarios.buscarPorId(cadastrador.id()).orElseThrow();

        assertThatThrownBy(() -> useCase.criar("joao", "João", perfil(Perfil.ROLE_USER), "provisoria1", null, quemCadastra))
                .isInstanceOf(AcessoNegadoException.class).hasMessageContaining("cuida dos acessos");
        Usuario joao = useCase.criar("joao", "João", List.of(), "provisoria1", null, quemCadastra);
        assertThat(joao.acesso().permissoes()).isEmpty();
        assertThat(horarios.listarPorUsuario(joao.id())).isEmpty();
        assertThat(acessos.historico).last().satisfies(m -> assertThat(m.descricao()).contains("sem perfil"));
    }

    @Test
    @DisplayName("Login inválido ou repetido, senha curta, perfil que não existe: recusados")
    void validacoes() {
        assertThatThrownBy(() -> useCase.criar("a b", "X", perfil(Perfil.ROLE_USER), "provisoria1", null, admin()))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> useCase.criar("eduardo", "X", perfil(Perfil.ROLE_USER), "provisoria1", null, admin()))
                .isInstanceOf(ConflitoException.class);
        assertThatThrownBy(() -> useCase.criar("joao", "João", perfil(Perfil.ROLE_USER), "123", null, admin()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("8 caracteres");
        assertThatThrownBy(() -> useCase.criar("joao", "João", List.of(java.util.UUID.randomUUID()), "provisoria1", null,
                admin())).hasMessageContaining("não existe mais");
    }

    @Test
    @DisplayName("Cada pessoa tem a sua pasta (a mesma escrita de outro jeito também conta); só o dono ou quem configura a equipe trocam")
    void pastaDeCadaUm() {
        useCase.definirPasta(USUARIO, "\\\\26.0.0.1\\Ponto", admin());
        Usuario maria = useCase.criar("maria", "Maria", perfil(Perfil.ROLE_USER), "provisoria1", null, admin());

        assertThatThrownBy(() -> useCase.definirPasta(maria.id(), "//26.0.0.1/ponto/", maria))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("Eduardo");
        assertThatThrownBy(() -> useCase.definirPasta(USUARIO, "C:/Outra", maria))
                .isInstanceOf(AcessoNegadoException.class);

        Usuario comPasta = useCase.definirPasta(maria.id(), " D:\\Comprovantes ", maria);
        assertThat(comPasta.pastaComprovantes()).isEqualTo("D:\\Comprovantes");
        assertThat(useCase.definirPasta(maria.id(), "  ", admin()).pastaComprovantes()).isNull();
    }

    @Test
    @DisplayName("Ninguém se desativa; desativada, a pessoa sai dos titulares e não perde o histórico")
    void desativar() {
        assertThatThrownBy(() -> useCase.atualizar(USUARIO, "Eduardo", false, admin()))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("próprio usuário");
        Usuario maria = useCase.criar("maria", "Maria", perfil(Perfil.ROLE_ADMIN), "provisoria1", null, admin());
        useCase.atualizar(USUARIO, "Eduardo", false, maria);
        assertThat(usuarios.buscarPorId(USUARIO).orElseThrow().ativo()).isFalse();

        Usuario joao = useCase.criar("joao", "João", perfil(Perfil.ROLE_USER), "provisoria1", null, maria);
        assertThat(useCase.titulares()).extracting(Usuario::login).containsExactly("joao", "maria");
        useCase.atualizar(joao.id(), "João", false, maria);
        assertThat(useCase.titulares()).extracting(Usuario::login).containsExactly("maria");
        assertThat(useCase.listar()).extracting(Usuario::login).containsExactly("maria", "eduardo", "joao");
    }

    @Test
    @DisplayName("Desativar ou trocar a senha de alguém só pode quem tem pelo menos os mesmos acessos que a pessoa")
    void naoMexeEmQuemTemMaisAcesso() {
        Usuario maria = useCase.criar("maria", "Maria", perfil(Perfil.ROLE_USER), "provisoria1", null, admin());
        Usuario cadastro = useCase.criar("cadastro", "Cadastro Geral", perfil(Perfil.ROLE_USER), "provisoria1", null, admin());
        gerenciarAcessos.definirAcesso(admin(), cadastro.id(), perfil(Perfil.ROLE_USER),
                List.of(new AjusteDeAcesso(PermissoesDaAdministracao.USUARIOS, true)));
        Usuario quemCadastra = usuarios.buscarPorId(cadastro.id()).orElseThrow();

        assertThatThrownBy(() -> useCase.redefinirSenha(USUARIO, "outrasenha1", quemCadastra))
                .isInstanceOf(AcessoNegadoException.class).hasMessageContaining("Eduardo tem acessos que você não tem");
        assertThatThrownBy(() -> useCase.atualizar(USUARIO, "Eduardo", false, quemCadastra))
                .isInstanceOf(AcessoNegadoException.class);

        assertThat(useCase.redefinirSenha(maria.id(), "outrasenha1", quemCadastra).trocarSenha()).isTrue();
        assertThat(useCase.atualizar(maria.id(), "Maria S.", false, quemCadastra).ativo()).isFalse();
        assertThat(useCase.atualizar(maria.id(), "Maria S.", true, quemCadastra).ativo()).isTrue();
    }
}
