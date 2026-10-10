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
import br.com.conferenciaponto.modulos.atendimento.application.arquivo.ArquivoView;
import br.com.conferenciaponto.modulos.atendimento.application.arquivo.GerenciarArquivos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
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

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

/** Rotas do processamento e dos arquivos: só para quem tem o gerador liberado, e os envios chegam em fluxo. */
@WebMvcTest(controllers = {ProcessamentoController.class, ArquivosController.class})
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class,
        EmissorDoGerador.class})
class ProcessamentoEArquivosWebTest {

    private static final UUID ID = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final UUID ARQUIVO = UUID.fromString("2a7e1c3d-0000-4000-8000-0000000000aa");
    private static final ProgressoView PROGRESSO = new ProgressoView(ID, "processando", null, 25,
            List.of(new ProgressoView.Arquivo(ARQUIVO, "baixando", null, null)));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarAcessosAoGerador acessos;
    @MockitoBean
    private ProcessarAtendimentos processamento;
    @MockitoBean
    private GerenciarArquivos arquivos;
    @MockitoBean
    private GerenciarAtendimentos atendimentos;

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
    void semSessaoOuSemOGeradorLiberado() throws Exception {
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/processar")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/atendimentos/eventos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/processar").with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        mvc.perform(put("/api/v1/atendimentos/" + ID + "/arquivos").with(como(maria)).content(PNG))
                .andExpect(status().isForbidden());
        verify(processamento, never()).processar(any(), any());
        verify(arquivos, never()).enviarExtra(any(), any(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void processarCancelarRetomarETentarDeNovo() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(processamento.processar(maria, ID)).thenReturn(PROGRESSO);
        when(processamento.cancelar(maria, ID)).thenReturn(PROGRESSO);
        when(processamento.retomar(maria, ID)).thenReturn(PROGRESSO);
        when(processamento.progresso(maria, ID)).thenReturn(PROGRESSO);
        when(processamento.tentarDeNovo(maria, ID, ARQUIVO)).thenThrow(new RegraNegocioException("LINK_VENCIDO", "venceu"));

        mvc.perform(post("/api/v1/atendimentos/" + ID + "/processar").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.percentual").value(25))
                .andExpect(jsonPath("$.dados.arquivos[0].situacao").value("baixando"));
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/cancelar").with(como(maria))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/retomar").with(como(maria))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/atendimentos/" + ID + "/progresso").with(como(maria))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/arquivos/" + ARQUIVO + "/tentar-de-novo").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("LINK_VENCIDO"));
    }

    @Test
    void atendimentoDeOutraPessoaVem404() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(processamento.processar(maria, ID)).thenThrow(new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO", "x"));

        mvc.perform(post("/api/v1/atendimentos/" + ID + "/processar").with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("ATENDIMENTO_NAO_ENCONTRADO"));
    }

    @Test
    void enviaUmExtraEmFluxoComOsParametros() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        byte[][] lido = new byte[1][];
        when(arquivos.enviarExtra(eq(maria), eq(ID), eq("print_extra"), eq("Captura de tela.png"), eq("1760011000000"), any(),
                eq((long) PNG.length))).thenAnswer(chamada -> {
                    lido[0] = chamada.<InputStream>getArgument(5).readAllBytes();
                    return new ArquivoView(ARQUIVO, "print_extra", 1, "Captura de tela.png", "imagem", "pronto",
                            (long) PNG.length, Instant.ofEpochMilli(1760011000000L), null);
                });

        mvc.perform(put("/api/v1/atendimentos/" + ID + "/arquivos").with(como(maria))
                        .param("origem", "print_extra").param("nome", "Captura de tela.png").param("modificadoEm", "1760011000000")
                        .contentType(MediaType.IMAGE_PNG).content(PNG))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dados.situacao").value("pronto"));

        assertThat(lido[0]).isEqualTo(PNG);
    }

    @Test
    void envioAMaoTirarERenovarOPdf() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(arquivos.enviarConteudo(eq(maria), eq(ID), eq(ARQUIVO), eq("foto do celular.jpg"), any(), anyLong()))
                .thenReturn(new ArquivoView(ARQUIVO, "anexo_conversa", 1, "1.jpeg", "imagem", "pronto", 3L, null, null));
        doThrow(new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO", "x")).when(arquivos).tirar(maria, ID, UUID.fromString(
                "2a7e1c3d-0000-4000-8000-0000000000bb"));
        when(atendimentos.renovarPdf(eq(maria), eq(ID), any(), anyLong(), eq("novo.pdf")))
                .thenThrow(new RegraNegocioException("CHAMADO_DIFERENTE", "outro chamado"));

        mvc.perform(put("/api/v1/atendimentos/" + ID + "/arquivos/" + ARQUIVO + "/conteudo").with(como(maria))
                        .header("X-Nome-Arquivo", "foto%20do%20celular.jpg").content(new byte[] {1, 2, 3}))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("pronto"));
        mvc.perform(delete("/api/v1/atendimentos/" + ID + "/arquivos/" + ARQUIVO).with(como(maria))).andExpect(status().isOk());
        verify(arquivos).tirar(maria, ID, ARQUIVO);
        mvc.perform(delete("/api/v1/atendimentos/" + ID + "/arquivos/2a7e1c3d-0000-4000-8000-0000000000bb").with(como(maria)))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/atendimentos/" + ID + "/pdf").with(como(maria)).header("X-Nome-Arquivo", "novo.pdf")
                        .contentType(MediaType.APPLICATION_PDF).content(new byte[] {'%', 'P'}))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("CHAMADO_DIFERENTE"));
    }

    @Test
    void oCanalDeEventosAbreParaQuemTemOGerador() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);

        mvc.perform(get("/api/v1/atendimentos/eventos").with(como(maria)).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
    }
}
