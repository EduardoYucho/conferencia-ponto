package br.com.conferenciaponto.modulos;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.web.AcessoAoGeradorController;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.web.WebDoGerador;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.security.SegurancaDaBase;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.web.AcessoABaseController;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.web.WebDaBase;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem entra nas rotas dos módulos: o mesmo login do ponto, a liberação de cada módulo (não o perfil do ponto),
 * a tela de acessos só para o administrador, e as regras do ponto sem mudança nenhuma.
 */
@WebMvcTest(controllers = {AcessoAoGeradorController.class, AcessoABaseController.class})
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class,
        SegurancaDaBase.class, WebDoGerador.class, WebDaBase.class, AcessosAosModulosWebTest.RotasDeTeste.class})
class AcessosAosModulosWebTest {

    /** Rotas comuns de cada módulo (as das próximas etapas nascem assim: protegidas pela liberação). */
    @RestController
    static class RotasDeTeste {
        @GetMapping("/api/v1/atendimentos/rota-de-teste")
        ApiResponse<String> lerDoGerador() {
            return ApiResponse.ok("gerador");
        }

        @PostMapping("/api/v1/atendimentos/rota-de-teste")
        ApiResponse<String> gravarNoGerador() {
            return ApiResponse.ok("gravado");
        }

        @GetMapping("/api/v1/conhecimento/rota-de-teste")
        ApiResponse<String> lerDaBase() {
            return ApiResponse.ok("base");
        }

        @PostMapping("/api/v1/conhecimento/rota-de-teste")
        ApiResponse<String> gravarNaBase() {
            return ApiResponse.ok("gravado");
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarAcessosAoGerador gerador;
    @MockitoBean
    private GerenciarAcessosABase base;

    private Usuario admin;
    private Usuario maria;
    private Usuario coordenacao;

    @BeforeEach
    void cadastrar() {
        admin = usuario("admin", Perfil.ROLE_ADMIN, true, false);
        maria = usuario("maria", Perfil.ROLE_USER, true, false);
        coordenacao = usuario("coordenacao", Perfil.ROLE_VIEWER, true, false);
        when(base.acessoDe(any())).thenAnswer(i -> AcessoABase.semAcesso(((Usuario) i.getArgument(0)).id()));
    }

    @Test
    void semSessaoNaoEntraEmNenhumModulo() throws Exception {
        mvc.perform(get("/api/v1/atendimentos/meu-acesso"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erros[0].codigo").value("NAO_AUTENTICADO"));
        mvc.perform(post("/api/v1/conhecimento/rota-de-teste"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void qualquerPerfilConsultaOProprioAcesso() throws Exception {
        when(gerador.liberado(coordenacao)).thenReturn(true);

        mvc.perform(get("/api/v1/atendimentos/meu-acesso").with(como(coordenacao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.gerador").value(true))
                .andExpect(jsonPath("$.dados.administrador").value(false));
        mvc.perform(get("/api/v1/conhecimento/meu-acesso").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.pesquisar").value(false));
    }

    @Test
    void oGeradorSoAbreParaQuemFoiLiberadoInclusiveOAdministrador() throws Exception {
        for (Usuario semLiberacao : new Usuario[] {maria, admin}) {
            mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(semLiberacao)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"))
                    .andExpect(jsonPath("$.protocolo").exists());
        }
        when(gerador.liberado(maria)).thenReturn(true);
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados").value("gerador"));
    }

    @Test
    void aCoordenacaoLiberadaGravaNoModulo() throws Exception {
        when(gerador.liberado(coordenacao)).thenReturn(true);
        when(base.acessoDe(coordenacao)).thenReturn(new AcessoABase(coordenacao.id(), true, false, "admin", null));

        mvc.perform(post("/api/v1/atendimentos/rota-de-teste").with(como(coordenacao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados").value("gravado"));
        mvc.perform(post("/api/v1/conhecimento/rota-de-teste").with(como(coordenacao)))
                .andExpect(status().isOk());
    }

    @Test
    void aBaseExigePoderPesquisar() throws Exception {
        mvc.perform(get("/api/v1/conhecimento/rota-de-teste").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        when(base.acessoDe(maria)).thenReturn(new AcessoABase(maria.id(), true, false, "admin", null));
        mvc.perform(get("/api/v1/conhecimento/rota-de-teste").with(como(maria)))
                .andExpect(status().isOk());
    }

    @Test
    void soOAdministradorVeEMudaOsAcessos() throws Exception {
        UUID id = maria.id();
        mvc.perform(get("/api/v1/atendimentos/acessos").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));
        mvc.perform(put("/api/v1/conhecimento/acessos/" + id).with(como(coordenacao))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pesquisar\":true,\"curar\":true}"))
                .andExpect(status().isForbidden());
        verify(base, never()).definir(any(), any(), anyBoolean(), anyBoolean());

        mvc.perform(get("/api/v1/atendimentos/acessos").with(como(admin))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/atendimentos/acessos/" + id).with(como(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gerador\":true}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/conhecimento/acessos/" + id).with(como(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pesquisar\":false,\"curar\":true}"))
                .andExpect(status().isOk());
        verify(gerador).definir(admin, id, true);
        verify(base).definir(admin, id, false, true);
    }

    @Test
    void pedidoSemOValorEAvisadoNoCampo() throws Exception {
        mvc.perform(put("/api/v1/atendimentos/acessos/" + maria.id()).with(como(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("gerador"));
    }

    @Test
    void senhaProvisoriaOuAcessoDesativadoBarramOsModulos() throws Exception {
        Usuario provisoria = usuario("nova", Perfil.ROLE_USER, true, true);
        when(gerador.liberado(provisoria)).thenReturn(true);
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(provisoria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("TROCAR_SENHA"));

        Usuario desativado = usuario("antigo", Perfil.ROLE_USER, false, false);
        mvc.perform(get("/api/v1/conhecimento/rota-de-teste").with(como(desativado)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_DESATIVADO"));
    }

    @Test
    void asRegrasDoPontoContinuamAsMesmas() throws Exception {
        when(gerador.liberado(any())).thenReturn(true);

        mvc.perform(post("/api/v1/jornadas/batidas").with(como(coordenacao)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));
        mvc.perform(post("/api/v1/usuarios").with(como(maria)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/jornadas"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------------------------------------- apoio

    private Usuario usuario(String login, Perfil perfil, boolean ativo, boolean trocarSenha) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, login, "hash", ativo, Set.of(perfil), null, null,
                trocarSenha);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    private static RequestPostProcessor como(Usuario usuario) {
        String perfil = usuario.perfis().iterator().next().name();
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(new SimpleGrantedAuthority(perfil));
    }
}
