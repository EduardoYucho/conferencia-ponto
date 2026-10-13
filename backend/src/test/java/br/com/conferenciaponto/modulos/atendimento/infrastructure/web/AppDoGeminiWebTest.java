package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
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

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
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

/** As rotas do texto pelo app do Gemini: só com o gerador liberado, os arquivos para anexar e a resposta colada. */
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
    private GerenciarAcessosAoGerador acessos;
    @MockitoBean
    private PeloAppDoGemini app;
    @MockitoBean
    private ProcessarAtendimentos processamento;

    private Usuario maria;

    @BeforeEach
    void cadastrar() {
        maria = new Usuario(UUID.randomUUID(), "maria", "maria", "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
        when(usuarios.buscarPorLogin("maria")).thenReturn(Optional.of(maria));
    }

    private static RequestPostProcessor como(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Test
    void semOGeradorLiberadoNadaPassa() throws Exception {
        when(acessos.liberado(maria)).thenReturn(false);

        mvc.perform(post(ROTA + "/resumo/pedido").with(como(maria))).andExpect(status().isForbidden());
        mvc.perform(get(ROTA + "/arquivos.zip").with(como(maria))).andExpect(status().isForbidden());
        mvc.perform(post(ROTA + "/resumo/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resposta\":\"{}\"}")).andExpect(status().isForbidden());
        verify(app, never()).usarResposta(any(), any(), any(), any());
    }

    @Test
    void montaOPedidoEUsaAResposta() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(app.pedido(maria, ID, TipoDeSaida.DEV_ERRO)).thenReturn(new PeloAppDoGemini.PedidoView("dev_erro", "Escreva...",
                List.of(new PeloAppDoGemini.PedidoView.Arquivo("Anexos.pdf", 2048, "as imagens (A1)")),
                List.of("A3 não está no servidor."), 0, 0));
        when(processamento.progresso(maria, ID)).thenReturn(new ProgressoView(ID, "concluido", null, 100, List.of()));

        mvc.perform(post(ROTA + "/dev_erro/pedido").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.pedido").value("Escreva..."))
                .andExpect(jsonPath("$.dados.arquivos[0].nome").value("Anexos.pdf"))
                .andExpect(jsonPath("$.dados.avisos[0]").value("A3 não está no servidor."));
        mvc.perform(post(ROTA + "/soneto/pedido").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("SAIDA_DESCONHECIDA"));
        mvc.perform(post(ROTA + "/dev_erro/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resposta\":\"```json\\n{}\\n```\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("concluido"));
        verify(app).usarResposta(maria, ID, TipoDeSaida.DEV_ERRO, "```json\n{}\n```");
    }

    @Test
    void respostaQueNaoServeVoltaComAMensagem() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(app.usarResposta(maria, ID, TipoDeSaida.RESUMO, "oi")).thenThrow(new RegraNegocioException(
                "RESPOSTA_FORA_DO_FORMATO", "A resposta não tem o bloco JSON."));

        mvc.perform(post(ROTA + "/resumo/resposta").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resposta\":\"oi\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("RESPOSTA_FORA_DO_FORMATO"));
    }

    @Test
    void osArquivosUmAUmEOZip() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        Path pdf = Files.write(pasta.resolve("Anexos.pdf"), new byte[]{'%', 'P', 'D', 'F', 1, 2});
        Path mp3 = Files.write(pasta.resolve("Audios.mp3"), new byte[]{'I', 'D', '3'});
        when(app.arquivo(maria, ID, "Anexos.pdf")).thenReturn(new PeloAppDoGemini.ArquivoDoPedido("Anexos.pdf", pdf, 6, ""));
        when(app.arquivo(maria, ID, "nada.txt")).thenThrow(new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO",
                "Arquivo não encontrado: monte o pedido de novo."));
        when(app.arquivos(maria, ID)).thenReturn(List.of(new PeloAppDoGemini.ArquivoDoPedido("Anexos.pdf", pdf, 6, ""),
                new PeloAppDoGemini.ArquivoDoPedido("Audios.mp3", mp3, 3, "")));

        mvc.perform(get(ROTA + "/arquivos/Anexos.pdf").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Anexos.pdf\""))
                .andExpect(content().bytes(Files.readAllBytes(pdf)));
        mvc.perform(get(ROTA + "/arquivos/nada.txt").with(como(maria)))
                .andExpect(status().isNotFound());

        byte[] zip = mvc.perform(get(ROTA + "/arquivos.zip").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"arquivos-para-o-gemini.zip\""))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> nomes = new ArrayList<>();
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                nomes.add(e.getName() + ":" + entrada.readAllBytes().length);
            }
        }
        assertThat(nomes).containsExactly("Anexos.pdf:6", "Audios.mp3:3");
    }

    @Test
    void zipDeAtendimentoSemPedidoAvisa() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(app.arquivos(maria, ID)).thenThrow(new RegraNegocioException("PEDIDO_SEM_ARQUIVOS",
                "Este pedido não tem arquivos para anexar."));

        mvc.perform(get(ROTA + "/arquivos.zip").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("PEDIDO_SEM_ARQUIVOS"));
    }
}
