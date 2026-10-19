package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.AcessoDaPessoaView;
import br.com.conferenciaponto.application.usecase.GerenciarAcessosUseCase.PerfilView;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As rotas dos perfis e do acesso de cada pessoa: só com "Perfis e acessos", e o que vai para o caso de uso. */
@WebMvcTest(controllers = AcessosController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class})
class AcessosWebTest {

    private static final UUID PERFIL = UUID.fromString("7c0f6a1e-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private GerenciarAcessosUseCase acessos;
    @MockitoBean
    private UsuarioRepository usuarios;

    private Usuario eduardo;
    private Usuario cadastro;

    @BeforeEach
    void cadastrar() {
        eduardo = usuario("eduardo", Acesso.administrador());
        cadastro = usuario("cadastro", Acesso.usuario().com(PermissoesDaAdministracao.USUARIOS));
    }

    private Usuario usuario(String login, Acesso acesso) {
        Usuario u = new Usuario(UUID.nameUUIDFromBytes(login.getBytes()), login, login, "x", true, acesso, null);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(u));
        return u;
    }

    private static RequestPostProcessor como(Usuario u) {
        return jwt().jwt(j -> j.subject(u.login())).authorities(u.acesso().permissoes().stream()
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    private static PerfilView perfil(String nome) {
        return new PerfilView(PERFIL, nome, null, false, false, null, List.of(PermissoesDoPonto.EQUIPE), List.of(),
                Instant.parse("2026-10-19T13:00:00Z"), "eduardo");
    }

    @Test
    void semPerfisEAcessosNaoEntraEmNada() throws Exception {
        mvc.perform(get("/api/v1/acessos/perfis")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/acessos/perfis").with(como(cadastro)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));
        mvc.perform(put("/api/v1/acessos/usuarios/" + eduardo.id()).with(como(cadastro))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"perfis\":[],\"ajustes\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/acessos/perfis/" + PERFIL).with(como(cadastro))).andExpect(status().isForbidden());
        verify(acessos, never()).definirAcesso(any(), any(), any(), any());
        verify(acessos, never()).excluirPerfil(any(), any());
    }

    @Test
    void asPermissoesVemEmGrupos() throws Exception {
        when(acessos.catalogo()).thenReturn(new CatalogoDePermissoes(List.of(new PermissoesDoPonto())).grupos());

        mvc.perform(get("/api/v1/acessos/permissoes").with(como(eduardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados[0].nome").value("Ponto"))
                .andExpect(jsonPath("$.dados[0].permissoes[1].codigo").value(PermissoesDoPonto.LANCAR))
                .andExpect(jsonPath("$.dados[0].permissoes[1].nome").value("Lançar no próprio ponto"))
                .andExpect(jsonPath("$.dados[0].permissoes[1].requer").value(PermissoesDoPonto.PROPRIO));
    }

    @Test
    void criaEAlteraOPerfilComQuemEstaLogado() throws Exception {
        when(acessos.criarPerfil(eduardo, "Coordenação", "Consulta", List.of(PermissoesDoPonto.EQUIPE))).thenReturn(perfil("Coordenação"));
        when(acessos.alterarPerfil(eduardo, PERFIL, "Coordenação 2", null, List.of())).thenReturn(perfil("Coordenação 2"));

        mvc.perform(post("/api/v1/acessos/perfis").with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Coordenação\",\"descricao\":\"Consulta\",\"permissoes\":[\"ponto.equipe\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dados.id").value(PERFIL.toString()))
                .andExpect(jsonPath("$.dados.permissoes[0]").value(PermissoesDoPonto.EQUIPE));
        mvc.perform(put("/api/v1/acessos/perfis/" + PERFIL).with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Coordenação 2\",\"permissoes\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.nome").value("Coordenação 2"));
        mvc.perform(post("/api/v1/acessos/perfis").with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissoes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("nome"));
        mvc.perform(delete("/api/v1/acessos/perfis/" + PERFIL).with(como(eduardo))).andExpect(status().isOk());
        verify(acessos).excluirPerfil(eduardo, PERFIL);
    }

    @Test
    void oAcessoDaPessoaVaiComOsPerfisEOsAjustes() throws Exception {
        UUID maria = UUID.randomUUID();
        AcessoDaPessoaView view = new AcessoDaPessoaView(maria, "Maria", "maria", true, List.of(PERFIL),
                List.of(new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false)),
                List.of(new CatalogoDePermissoes.Origem(PermissoesDoPonto.LANCAR, false, List.of("Usuário"), false, null)));
        when(acessos.definirAcesso(eduardo, maria, List.of(PERFIL), List.of(new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false),
                new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, true)))).thenReturn(view);

        mvc.perform(put("/api/v1/acessos/usuarios/" + maria).with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfis\":[\"" + PERFIL + "\"],\"ajustes\":[{\"permissao\":\"ponto.lancar\",\"liberada\":false},"
                                + "{\"permissao\":\"logs.ver\",\"liberada\":true}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.perfis[0]").value(PERFIL.toString()))
                .andExpect(jsonPath("$.dados.ajustes[0].liberada").value(false))
                .andExpect(jsonPath("$.dados.permissoes[0].perfis[0]").value("Usuário"))
                .andExpect(jsonPath("$.dados.permissoes[0].pode").value(false));

        mvc.perform(put("/api/v1/acessos/usuarios/" + maria).with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfis\":[],\"ajustes\":[{\"permissao\":\"Ponto Lancar\",\"liberada\":true}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("PERMISSAO_DESCONHECIDA"));
        mvc.perform(put("/api/v1/acessos/usuarios/" + maria).with(como(eduardo)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perfis\":[],\"ajustes\":[{\"permissao\":\"logs.ver\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historicoComLimite() throws Exception {
        when(acessos.historico(null, 20)).thenReturn(List.of(new GerenciarAcessosUseCase.MudancaView(
                Instant.parse("2026-10-19T13:00:00Z"), "eduardo", null, null, PERFIL, "Perfil X criado.")));

        mvc.perform(get("/api/v1/acessos/historico").param("limite", "20").with(como(eduardo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados[0].descricao").value("Perfil X criado."));
    }
}
