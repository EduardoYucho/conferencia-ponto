package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import br.com.conferenciaponto.modulos.atendimento.application.chave.ChaveGeminiView;
import br.com.conferenciaponto.modulos.atendimento.application.chave.GerenciarChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

/** Rotas da chave do Gemini: só para quem tem o gerador liberado, cada um a própria, e a chave nunca volta. */
@WebMvcTest(controllers = ChaveGeminiController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class})
class ChaveGeminiWebTest {

    private static final String CHAVE = "AIzaSyD-chave-de-teste_0123456789abcd";
    private static final ChaveGeminiView CADASTRADA = new ChaveGeminiView(true, "abcd", true, "valida",
            Instant.parse("2026-10-09T18:00:00Z"), Instant.parse("2026-10-09T18:00:00Z"), true, "O Google aceitou a chave.");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarAcessosAoGerador acessos;
    @MockitoBean
    private GerenciarChaveGemini chaves;

    private Usuario maria;
    private Usuario coordenacao;

    @BeforeEach
    void cadastrar() {
        maria = usuario("maria", Perfil.ROLE_USER);
        coordenacao = usuario("coordenacao", Perfil.ROLE_VIEWER);
    }

    @Test
    void semSessao() throws Exception {
        mvc.perform(get("/api/v1/atendimentos/chave-gemini")).andExpect(status().isUnauthorized());
    }

    @Test
    void semOGeradorLiberado() throws Exception {
        mvc.perform(get("/api/v1/atendimentos/chave-gemini").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        mvc.perform(put("/api/v1/atendimentos/chave-gemini").with(como(maria))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(CHAVE, true)))
                .andExpect(status().isForbidden());
        verify(chaves, never()).cadastrar(any(), anyString(), anyBoolean());
    }

    @Test
    void cadastraSemDevolverAChave() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(chaves.cadastrar(maria, CHAVE, true)).thenReturn(CADASTRADA);

        String resposta = mvc.perform(put("/api/v1/atendimentos/chave-gemini").with(como(maria))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(CHAVE, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.ultimosCaracteres").value("abcd"))
                .andExpect(jsonPath("$.dados.situacao").value("valida"))
                .andReturn().getResponse().getContentAsString();

        assertThat(resposta).doesNotContain(CHAVE);
    }

    @Test
    void aCoordenacaoLiberadaTambemCadastra() throws Exception {
        when(acessos.liberado(coordenacao)).thenReturn(true);
        when(chaves.cadastrar(coordenacao, CHAVE, true)).thenReturn(CADASTRADA);

        mvc.perform(put("/api/v1/atendimentos/chave-gemini").with(como(coordenacao))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(CHAVE, true)))
                .andExpect(status().isOk());
    }

    @Test
    void chaveVaziaERecusadaComMensagem() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);

        mvc.perform(put("/api/v1/atendimentos/chave-gemini").with(como(maria))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo("", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].mensagem").value("Cole a chave da API do Gemini."));
    }

    @Test
    void recusaDoGoogleChegaComOCodigoEAMensagem() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(chaves.cadastrar(eq(maria), anyString(), anyBoolean()))
                .thenThrow(new RegraNegocioException("CHAVE_GEMINI_RECUSADA", "O Google recusou a chave. Ela não foi guardada."));

        String resposta = mvc.perform(put("/api/v1/atendimentos/chave-gemini").with(como(maria))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo(CHAVE, true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("CHAVE_GEMINI_RECUSADA"))
                .andExpect(jsonPath("$.protocolo").exists())
                .andReturn().getResponse().getContentAsString();

        assertThat(resposta).doesNotContain(CHAVE);
    }

    @Test
    void consultaTestaEApagaAPropria() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(chaves.estado(maria)).thenReturn(CADASTRADA);
        when(chaves.testar(maria)).thenReturn(CADASTRADA);
        when(chaves.apagar(maria)).thenReturn(new ChaveGeminiView(false, null, false, null, null, null, true, null));

        mvc.perform(get("/api/v1/atendimentos/chave-gemini").with(como(maria)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dados.cadastrada").value(true));
        mvc.perform(post("/api/v1/atendimentos/chave-gemini/testar").with(como(maria)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dados.mensagem").value("O Google aceitou a chave."));
        mvc.perform(delete("/api/v1/atendimentos/chave-gemini").with(como(maria)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dados.cadastrada").value(false));
    }

    @Test
    void oPedidoNaoMostraAChaveNoTexto() {
        assertThat(new ChaveGeminiController.CadastrarChaveRequest(CHAVE, true).toString()).doesNotContain(CHAVE);
    }

    // --------------------------------------------------------------------------------------------- apoio

    private static String corpo(String chave, boolean nivelPago) {
        return "{\"chave\": \"" + chave + "\", \"nivelPagoConfirmado\": " + nivelPago + "}";
    }

    private Usuario usuario(String login, Perfil perfil) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, login, "hash", true, Set.of(perfil), null, null, false);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    private static RequestPostProcessor como(Usuario usuario) {
        String perfil = usuario.perfis().iterator().next().name();
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(new SimpleGrantedAuthority(perfil));
    }
}
