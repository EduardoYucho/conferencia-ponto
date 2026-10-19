package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.PeloAppDoGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As rotas do texto pelo app do Gemini: só com o gerador liberado, a habilidade, o pacote e a resposta colada. */
@WebMvcTest(controllers = AppDoGeminiController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class})
class AppDoGeminiWebTest {

    private static final UUID ID = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final String ROTA = "/api/v1/atendimentos/" + ID + "/app-gemini";

    @TempDir
    Path pasta;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private PeloAppDoGemini app;
    @MockitoBean
    private ProcessarAtendimentos processamento;

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
    void semOGeradorLiberadoNadaPassa() throws Exception {

        mvc.perform(post(ROTA + "/pacote").with(como(maria))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/atendimentos/app-gemini/habilidade").with(como(maria))).andExpect(status().isForbidden());
        mvc.perform(get(ROTA + "/arquivos/Atendimento_1.pdf").with(como(maria))).andExpect(status().isForbidden());
        mvc.perform(post(ROTA + "/resumo/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resposta\":\"{}\"}")).andExpect(status().isForbidden());
        verify(app, never()).usarResposta(any(), any(), any(), any());
    }

    @Test
    void asInstrucoesOPacoteEAResposta() throws Exception {
        maria = liberar(maria);
        when(app.habilidade()).thenReturn(new PeloAppDoGemini.Habilidade(2, "resumo-de-atendimento", "Você escreve os textos...",
                "---\nname: resumo-de-atendimento\n---\n\nVocê escreve os textos..."));
        when(app.pacote(maria, ID)).thenReturn(new PeloAppDoGemini.PacoteView(
                List.of(new PeloAppDoGemini.PacoteView.Arquivo("Atendimento_1.pdf", 2048, "a conversa (A1)")),
                List.of("A3 não está no servidor."), 0, 0, 2));
        when(processamento.progresso(maria, ID)).thenReturn(new ProgressoView(ID, "concluido", null, 100, List.of()));

        mvc.perform(get("/api/v1/atendimentos/app-gemini/habilidade").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.versao").value(2))
                .andExpect(jsonPath("$.dados.nome").value("resumo-de-atendimento"))
                .andExpect(jsonPath("$.dados.skillMd").value(org.hamcrest.Matchers.startsWith("---\nname: resumo-de-atendimento")));
        mvc.perform(post(ROTA + "/pacote").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.arquivos[0].nome").value("Atendimento_1.pdf"))
                .andExpect(jsonPath("$.dados.avisos[0]").value("A3 não está no servidor."))
                .andExpect(jsonPath("$.dados.versaoDaHabilidade").value(2));
        mvc.perform(post(ROTA + "/soneto/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resposta\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("SAIDA_DESCONHECIDA"));
        mvc.perform(post(ROTA + "/dev_erro/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resposta\":\"Assunto: x\\n\\nTexto.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("concluido"));
        verify(app).usarResposta(maria, ID, TipoDeSaida.DEV_ERRO, "Assunto: x\n\nTexto.");
    }

    @Test
    void respostaQueNaoServeVoltaComAMensagem() throws Exception {
        maria = liberar(maria);
        when(app.usarResposta(maria, ID, TipoDeSaida.RESUMO, "oi")).thenThrow(new RegraNegocioException(
                "RESPOSTA_SEM_TEXTO", "A resposta não tem nenhum trecho de texto."));

        mvc.perform(post(ROTA + "/resumo/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resposta\":\"oi\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("RESPOSTA_SEM_TEXTO"));
    }

    @Test
    void osArquivosDoPacote() throws Exception {
        maria = liberar(maria);
        Path pdf = Files.write(pasta.resolve("Atendimento_1.pdf"), new byte[]{'%', 'P', 'D', 'F', 1, 2});
        when(app.arquivo(maria, ID, "Atendimento_1.pdf")).thenReturn(new PeloAppDoGemini.ArquivoDoPacote("Atendimento_1.pdf", pdf, 6, ""));
        when(app.arquivo(maria, ID, "nada.txt")).thenThrow(new RecursoNaoEncontradoException("ARQUIVO_DO_PACOTE_NAO_ENCONTRADO",
                "Este arquivo não está no pacote."));

        mvc.perform(get(ROTA + "/arquivos/Atendimento_1.pdf").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Atendimento_1.pdf\""))
                .andExpect(content().bytes(Files.readAllBytes(pdf)));
        mvc.perform(get(ROTA + "/arquivos/nada.txt").with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("ARQUIVO_DO_PACOTE_NAO_ENCONTRADO"));
    }
}
