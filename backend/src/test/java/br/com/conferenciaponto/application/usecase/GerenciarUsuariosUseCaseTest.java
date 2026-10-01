package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
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

    private final UsuarioRepositoryEmMemoria usuarios = UsuarioRepositoryEmMemoria.comTitular();
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
    private final GerenciarUsuariosUseCase useCase = new GerenciarUsuariosUseCase(usuarios, codificador, horarios,
            regras, gerenciarCiclos, eventos::add, clock);

    private Usuario admin() {
        return usuarios.buscarPorId(USUARIO).orElseThrow();
    }

    @Test
    @DisplayName("Admin cria o acesso com senha provisória; o novo titular ganha horário padrão e ciclo do banco")
    void criaTitular() {
        Usuario maria = useCase.criar(" Maria.Souza ", "Maria Souza", Perfil.ROLE_USER, "provisoria1",
                "\\\\SERVIDOR\\Ponto\\Maria", "eduardo");

        assertThat(maria.login()).isEqualTo("maria.souza");
        assertThat(maria.trocarSenha()).isTrue();
        assertThat(codificador.confere("provisoria1", maria.senhaHash())).isTrue();
        assertThat(maria.pastaComprovantes()).isEqualTo("\\\\SERVIDOR\\Ponto\\Maria");
        assertThat(horarios.listarPorUsuario(maria.id())).singleElement()
                .satisfies(h -> assertThat(h.previstoSegundos(LocalDate.of(2026, 9, 28))).isEqualTo(31_680));
        // ciclo da empresa que contém hoje (25/11/2025 + 6 meses = 25/05/2026)
        assertThat(ciclos.buscarAberto(maria.id())).get()
                .satisfies(c -> assertThat(c.dataInicio()).isEqualTo(LocalDate.of(2026, 5, 25)));
        assertThat(eventos).last().isInstanceOf(UsuarioAlteradoEvento.class);
    }

    @Test
    @DisplayName("Coordenação (só consulta) não tem horário, ciclo nem pasta")
    void criaCoordenacao() {
        Usuario coord = useCase.criar("coordenacao", "Coordenação", Perfil.ROLE_VIEWER, "provisoria1", null, "eduardo");

        assertThat(horarios.listarPorUsuario(coord.id())).isEmpty();
        assertThat(ciclos.buscarAberto(coord.id())).isEmpty();
        assertThatThrownBy(() -> useCase.definirPasta(coord.id(), "C:/Ponto", admin()))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Login inválido ou repetido, senha curta: recusados")
    void validacoes() {
        assertThatThrownBy(() -> useCase.criar("a b", "X", Perfil.ROLE_USER, "provisoria1", null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> useCase.criar("eduardo", "X", Perfil.ROLE_USER, "provisoria1", null, "eduardo"))
                .isInstanceOf(ConflitoException.class);
        assertThatThrownBy(() -> useCase.criar("joao", "João", Perfil.ROLE_USER, "123", null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("8 caracteres");
    }

    @Test
    @DisplayName("Cada pessoa tem a sua pasta (a mesma escrita de outro jeito também conta); só o dono ou o admin trocam")
    void pastaDeCadaUm() {
        useCase.definirPasta(USUARIO, "\\\\26.0.0.1\\Ponto", admin());
        Usuario maria = useCase.criar("maria", "Maria", Perfil.ROLE_USER, "provisoria1", null, "eduardo");

        assertThatThrownBy(() -> useCase.definirPasta(maria.id(), "//26.0.0.1/ponto/", maria))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("Eduardo");
        assertThatThrownBy(() -> useCase.definirPasta(USUARIO, "C:/Outra", maria))
                .isInstanceOf(AcessoNegadoException.class);

        Usuario comPasta = useCase.definirPasta(maria.id(), " D:\\Comprovantes ", maria);
        assertThat(comPasta.pastaComprovantes()).isEqualTo("D:\\Comprovantes");
        assertThat(useCase.definirPasta(maria.id(), "  ", admin()).pastaComprovantes()).isNull();
    }

    @Test
    @DisplayName("Admin não se desativa nem tira o próprio perfil; sempre sobra um administrador ativo")
    void sempreUmAdministrador() {
        Usuario maria = useCase.criar("maria", "Maria", Perfil.ROLE_ADMIN, "provisoria1", null, "eduardo");

        assertThatThrownBy(() -> useCase.atualizar(USUARIO, "Eduardo", Perfil.ROLE_USER, true, USUARIO, "eduardo"))
                .isInstanceOf(RegraNegocioException.class);
        useCase.atualizar(maria.id(), "Maria", Perfil.ROLE_USER, true, USUARIO, "eduardo");
        assertThat(useCase.titulares()).extracting(Usuario::login).containsExactly("eduardo", "maria");

        useCase.atualizar(maria.id(), "Maria", Perfil.ROLE_USER, false, USUARIO, "eduardo");
        assertThat(useCase.titulares()).extracting(Usuario::login).containsExactly("eduardo");
        assertThat(useCase.listar()).extracting(Usuario::login).containsExactly("eduardo", "maria");
    }
}
