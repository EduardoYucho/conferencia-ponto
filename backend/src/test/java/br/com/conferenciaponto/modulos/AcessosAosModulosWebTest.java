package br.com.conferenciaponto.modulos;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.application.espaco.EspacoDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.web.AcessoAoGeradorController;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.web.WebDoGerador;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.PermissoesDaBase;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.security.SegurancaDaBase;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.web.AcessoABaseController;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.web.WebDaBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quem entra nas rotas dos módulos: o mesmo login do ponto e as permissões de cada módulo (atendimentos.usar,
 * base.pesquisar), que vêm dos perfis ou de um ajuste só da pessoa; o espaço em disco só com atendimentos.espaco;
 * e as regras do ponto sem mudança nenhuma.
 */
@WebMvcTest(controllers = {AcessoAoGeradorController.class, AcessoABaseController.class})
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class,
        SegurancaDaBase.class, WebDoGerador.class, WebDaBase.class, AcessosAosModulosWebTest.RotasDeTeste.class})
class AcessosAosModulosWebTest {

    /** Rotas comuns de cada módulo (as das próximas etapas nascem assim: protegidas pela permissão). */
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
    private EspacoDosAtendimentos espaco;

    private Usuario admin;
    private Usuario maria;
    private Usuario coordenacao;

    @BeforeEach
    void cadastrar() {
        admin = usuario("admin", Acesso.administrador().com(PermissoesDoAtendimento.USAR, PermissoesDoAtendimento.ESPACO,
                PermissoesDaBase.PESQUISAR, PermissoesDaBase.CURAR), true, false);
        maria = usuario("maria", Acesso.usuario(), true, false);
        coordenacao = usuario("coordenacao", Acesso.coordenador(), true, false);
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
    void soQuemTemAPermissaoVeOEspacoOcupadoPelosAtendimentos() throws Exception {
        when(espaco.consultar(admin)).thenReturn(new EspacoDosAtendimentos.EspacoView(1024, 4096, 0));

        mvc.perform(get("/api/v1/atendimentos/espaco").with(como(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.ocupado").value(1024))
                .andExpect(jsonPath("$.dados.livre").value(4096));
        Usuario soOGerador = usuario("ana", Acesso.usuario().com(PermissoesDoAtendimento.USAR), true, false);
        mvc.perform(get("/api/v1/atendimentos/espaco").with(como(soOGerador)))
                .andExpect(status().isForbidden());
        // a tela de acessos dos módulos saiu: os acessos ficam nos perfis do ponto
        mvc.perform(get("/api/v1/atendimentos/acessos").with(como(admin))).andExpect(status().isNotFound());
    }

    @Test
    void qualquerPessoaConsultaOProprioAcesso() throws Exception {
        coordenacao = usuario("coordenacao", Acesso.coordenador().com(PermissoesDoAtendimento.USAR), true, false);

        mvc.perform(get("/api/v1/atendimentos/meu-acesso").with(como(coordenacao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.gerador").value(true))
                .andExpect(jsonPath("$.dados.espaco").value(false));
        mvc.perform(get("/api/v1/conhecimento/meu-acesso").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.pesquisar").value(false))
                .andExpect(jsonPath("$.dados.curar").value(false));
        mvc.perform(get("/api/v1/conhecimento/meu-acesso").with(como(admin)))
                .andExpect(jsonPath("$.dados.curar").value(true));
    }

    @Test
    void oGeradorSoAbreParaQuemTemAPermissao() throws Exception {
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"))
                .andExpect(jsonPath("$.protocolo").exists());
        maria = usuario("maria", Acesso.usuario().com(PermissoesDoAtendimento.USAR), true, false);
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados").value("gerador"));
    }

    @Test
    void retirarAPermissaoValeNaHora() throws Exception {
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(admin))).andExpect(status().isOk());
        admin = usuario("admin", admin.acesso().sem(PermissoesDoAtendimento.USAR), true, false);
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
    }

    @Test
    void quemSoConsultaOPontoGravaNoModuloSeTemAPermissao() throws Exception {
        coordenacao = usuario("coordenacao", Acesso.coordenador().com(PermissoesDoAtendimento.USAR, PermissoesDaBase.PESQUISAR),
                true, false);

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
        maria = usuario("maria", Acesso.usuario().com(PermissoesDaBase.PESQUISAR), true, false);
        mvc.perform(get("/api/v1/conhecimento/rota-de-teste").with(como(maria)))
                .andExpect(status().isOk());
    }

    @Test
    void senhaProvisoriaOuAcessoDesativadoBarramOsModulos() throws Exception {
        Usuario provisoria = usuario("nova", Acesso.usuario().com(PermissoesDoAtendimento.USAR), true, true);
        mvc.perform(get("/api/v1/atendimentos/rota-de-teste").with(como(provisoria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("TROCAR_SENHA"));

        Usuario desativado = usuario("antigo", Acesso.usuario().com(PermissoesDaBase.PESQUISAR), false, false);
        mvc.perform(get("/api/v1/conhecimento/rota-de-teste").with(como(desativado)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_DESATIVADO"));
    }

    @Test
    void asRegrasDoPontoContinuamAsMesmas() throws Exception {
        coordenacao = usuario("coordenacao", Acesso.coordenador().com(PermissoesDoAtendimento.USAR), true, false);

        mvc.perform(post("/api/v1/jornadas/batidas").with(como(coordenacao)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));
        mvc.perform(post("/api/v1/usuarios").with(como(maria)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/jornadas"))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------------------------------------------------- apoio

    private Usuario usuario(String login, Acesso acesso, boolean ativo, boolean trocarSenha) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, login, "hash", ativo, acesso, null, null, trocarSenha);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    /** As authorities do token são as permissões da pessoa (o conversor de verdade as relê do banco). */
    private static RequestPostProcessor como(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(usuario.acesso().permissoes().stream()
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }
}
