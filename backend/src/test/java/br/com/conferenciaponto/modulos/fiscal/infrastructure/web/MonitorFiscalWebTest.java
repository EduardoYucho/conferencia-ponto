package br.com.conferenciaponto.modulos.fiscal.infrastructure.web;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.fiscal.application.acesso.PermissoesDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.LerAgora;
import br.com.conferenciaponto.modulos.fiscal.application.consulta.ConsultarMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.infrastructure.security.SegurancaDoMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quem entra nas rotas do monitor: sessão, fiscal.ver em tudo e fiscal.configurar para ler agora. */
@WebMvcTest(controllers = MonitorFiscalController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoMonitor.class,
        WebDoMonitor.class})
class MonitorFiscalWebTest {

    private static final Instant T = Instant.parse("2026-11-09T19:50:00Z");

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private ConsultarMonitor consulta;
    @MockitoBean
    private LerAgora lerAgora;

    private Usuario maria;
    private Usuario ana;
    private Usuario admin;

    @BeforeEach
    void cadastrar() {
        maria = usuario("maria", Acesso.usuario(), true, false);
        ana = usuario("ana", Acesso.usuario().com(PermissoesDoMonitor.VER), true, false);
        admin = usuario("admin", Acesso.administrador().com(PermissoesDoMonitor.VER, PermissoesDoMonitor.CONFIGURAR), true,
                false);
    }

    @Test
    void semSessaoNaoEntra() throws Exception {
        mvc.perform(get("/api/v1/fiscal/painel"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.erros[0].codigo").value("NAO_AUTENTICADO"));
        mvc.perform(get("/api/v1/fiscal/meu-acesso")).andExpect(status().isUnauthorized());
    }

    @Test
    void meuAcessoParaQualquerPessoa() throws Exception {
        mvc.perform(get("/api/v1/fiscal/meu-acesso").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.ver").value(false))
                .andExpect(jsonPath("$.dados.configurar").value(false));
        mvc.perform(get("/api/v1/fiscal/meu-acesso").with(como(admin)))
                .andExpect(jsonPath("$.dados.ver").value(true))
                .andExpect(jsonPath("$.dados.configurar").value(true));
    }

    @Test
    void oPainelExigeVer() throws Exception {
        when(consulta.incidentes(anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/fiscal/incidentes").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"))
                .andExpect(jsonPath("$.protocolo").exists());
        mvc.perform(get("/api/v1/fiscal/incidentes?dias=7").with(como(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados").isArray());
        verify(consulta).incidentes(7);
    }

    @Test
    void periodoForaDoLimiteVira422() throws Exception {
        when(consulta.disponibilidade(400)).thenThrow(new RegraNegocioException("PERIODO_INVALIDO", "O período vai de 1 a 90 dias."));
        mvc.perform(get("/api/v1/fiscal/disponibilidade?dias=400").with(como(ana)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("PERIODO_INVALIDO"));
    }

    @Test
    void lerAgoraSoComConfigurar() throws Exception {
        mvc.perform(post("/api/v1/fiscal/coletar").with(como(ana)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("SEM_PERMISSAO"));
        verify(lerAgora, never()).executar();

        when(lerAgora.executar()).thenReturn(new RegistroDoMonitor.Rodada(T, T.plusSeconds(4), 120, 1, false));
        mvc.perform(post("/api/v1/fiscal/coletar").with(como(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.fontesComProblema").value(1))
                .andExpect(jsonPath("$.dados.semInternet").value(false));
    }

    @Test
    void oPainelSaiComAsDatasEmTexto() throws Exception {
        when(consulta.painel()).thenReturn(new ConsultarMonitor.PainelView(T, true, 5,
                new ConsultarMonitor.RodadaView(T, T.plusSeconds(3), false, 0), List.of(), List.of(), List.of(),
                new ConsultarMonitor.ContagemView(1, 0, 0, 0), List.of(), List.of(new ConsultarMonitor.AvisoView("NFE",
                "NF-e", "Não há manutenção prevista.", T)), false));
        mvc.perform(get("/api/v1/fiscal/painel").with(como(ana)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.ultimaRodada.inicio").value("2026-11-09T19:50:00Z"))
                .andExpect(jsonPath("$.dados.intervaloMinutos").value(5))
                .andExpect(jsonPath("$.dados.avisos[0].texto").value("Não há manutenção prevista."));
    }

    @Test
    void retirarOAcessoValeNaHora() throws Exception {
        when(consulta.incidentes(anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/fiscal/incidentes").with(como(ana))).andExpect(status().isOk());
        ana = usuario("ana", ana.acesso().sem(PermissoesDoMonitor.VER), true, false);
        mvc.perform(get("/api/v1/fiscal/incidentes").with(como(ana)))
                .andExpect(status().isForbidden());
        Usuario provisoria = usuario("nova", Acesso.usuario().com(PermissoesDoMonitor.VER), true, true);
        mvc.perform(get("/api/v1/fiscal/painel").with(como(provisoria)))
                .andExpect(jsonPath("$.erros[0].codigo").value("TROCAR_SENHA"));
    }

    private Usuario usuario(String login, Acesso acesso, boolean ativo, boolean trocarSenha) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, login, "hash", ativo, acesso, null, null, trocarSenha);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    private static RequestPostProcessor como(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(usuario.acesso().permissoes().stream()
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }
}
