package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.application.analise.AnaliseView;
import br.com.conferenciaponto.modulos.atendimento.application.analise.ConsultarAnalise;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A análise de um arquivo: só com o gerador liberado e só a do dono. */
@WebMvcTest(controllers = AnalisesController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class})
class AnalisesWebTest {

    private static final UUID ID = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final UUID ARQUIVO = UUID.fromString("2a7e1c3d-0000-4000-8000-0000000000aa");
    private static final String ROTA = "/api/v1/atendimentos/" + ID + "/arquivos/" + ARQUIVO + "/analise";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private ConsultarAnalise analises;

    private Usuario maria;

    @BeforeEach
    void cadastrar() {
        maria = new Usuario(UUID.randomUUID(), "maria", "maria", "hash", true, Acesso.usuario(), null, null, false);
        when(usuarios.buscarPorLogin("maria")).thenReturn(Optional.of(maria));
    }

    private static RequestPostProcessor como(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(usuario.acesso().permissoes().stream()
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    /** Libera os atendimentos para a pessoa (como um ajuste só dela): o "banco" passa a devolvê-la com a permissão. */
    private Usuario liberar(Usuario usuario) {
        Usuario liberado = usuario.comAcesso(usuario.acesso().com(PermissoesDoAtendimento.USAR));
        when(usuarios.buscarPorLogin(usuario.login())).thenReturn(Optional.of(liberado));
        return liberado;
    }

    @Test
    void semSessaoOuSemOGeradorLiberado() throws Exception {
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        verify(analises, never()).doArquivo(any(), any(), any());
    }

    @Test
    void devolveAAnaliseComOResultadoEmJson() throws Exception {
        maria = liberar(maria);
        when(analises.doArquivo(maria, ID, ARQUIVO)).thenReturn(new AnaliseView(ARQUIVO, "audio", 1, "gemini-3.8-flash", true,
                Instant.parse("2026-10-10T15:00:00Z"), 1000, 200, 3200,
                new ObjectMapper().readTree("{\"trechos\":[{\"inicio\":\"00:00\",\"falante\":\"Falante 1\",\"texto\":\"Bom dia\"}]}")));

        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.categoria").value("audio"))
                .andExpect(jsonPath("$.dados.modelo").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.dados.tokensEntrada").value(1000))
                .andExpect(jsonPath("$.dados.resultado.trechos[0].texto").value("Bom dia"));
    }

    @Test
    void arquivoSemAnaliseOuDeOutraPessoaVem404() throws Exception {
        maria = liberar(maria);
        when(analises.doArquivo(maria, ID, ARQUIVO))
                .thenThrow(new RecursoNaoEncontradoException("ANALISE_NAO_ENCONTRADA", "Este arquivo ainda não foi analisado."));

        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("ANALISE_NAO_ENCONTRADA"));
    }

    @Test
    void quadroDoVideoVemComoPngEOQueNaoExisteVem404(@org.junit.jupiter.api.io.TempDir java.nio.file.Path pasta) throws Exception {
        maria = liberar(maria);
        java.nio.file.Path png = java.nio.file.Files.write(pasta.resolve("000012000.png"),
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
        when(analises.quadro(maria, ID, ARQUIVO, 12_000)).thenReturn(png);
        when(analises.quadro(maria, ID, ARQUIVO, 13_000)).thenThrow(new RecursoNaoEncontradoException("QUADRO_NAO_ENCONTRADO",
                "Este quadro do vídeo não existe."));
        String base = "/api/v1/atendimentos/" + ID + "/arquivos/" + ARQUIVO + "/quadros/";

        mvc.perform(get(base + "12000").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("image/png"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes(java.nio.file.Files.readAllBytes(png)));
        mvc.perform(get(base + "13000").with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("QUADRO_NAO_ENCONTRADO"));
        mvc.perform(get(base + "12000")).andExpect(status().isUnauthorized());
    }
}
