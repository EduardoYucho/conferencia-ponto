package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.GerenciarAcessosAoGerador;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.AtendimentoView;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.LeituraView;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.ResultadoDoEnvio;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.ResumoView;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.LeituraDoPdfException;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.security.SegurancaDoGerador;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rotas dos atendimentos: só para quem tem o gerador liberado, cada um os próprios, e o PDF chega em fluxo. */
@WebMvcTest(controllers = AtendimentoController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class})
class AtendimentoWebTest {

    private static final byte[] PDF = "%PDF-1.7 conteúdo de teste".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final UUID ID = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final ResumoView RESUMO = new ResumoView(ID, "20261009000001", "Loja Exemplo", null, null, "novo",
            Instant.parse("2026-10-09T15:00:00Z"), 14, 4, Instant.parse("2026-10-10T12:00:00Z"), false);
    private static final LeituraView LEITURA = new LeituraView("Conversa.pdf", "20261009000001", "Loja Exemplo", null, null,
            null, 14, 3, 4, Map.of("imagem", 1), Instant.parse("2026-10-10T12:00:00Z"), false, new Omitidos(1, 2), 0);

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarAcessosAoGerador acessos;
    @MockitoBean
    private GerenciarAtendimentos atendimentos;

    private Usuario maria;
    private Usuario coordenacao;

    @BeforeEach
    void cadastrar() {
        maria = usuario("maria", Perfil.ROLE_USER);
        coordenacao = usuario("coordenacao", Perfil.ROLE_VIEWER);
    }

    @Test
    void semSessao() throws Exception {
        mvc.perform(get("/api/v1/atendimentos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/atendimentos").contentType(MediaType.APPLICATION_PDF).content(PDF))
                .andExpect(status().isUnauthorized());
        verify(atendimentos, never()).receberPdf(any(), any(), anyLong(), any());
    }

    @Test
    void semOGeradorLiberado() throws Exception {
        mvc.perform(post("/api/v1/atendimentos").with(como(maria)).contentType(MediaType.APPLICATION_PDF).content(PDF))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        mvc.perform(get("/api/v1/atendimentos").with(como(maria))).andExpect(status().isForbidden());
        verify(atendimentos, never()).receberPdf(any(), any(), anyLong(), any());
    }

    @Test
    void enviaOPdfEmFluxoComONomeCodificado() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        ArgumentCaptor<InputStream> corpo = ArgumentCaptor.forClass(InputStream.class);
        byte[][] lido = new byte[1][];
        when(atendimentos.receberPdf(eq(maria), corpo.capture(), eq((long) PDF.length), eq("Conversa do cliente (1).pdf")))
                .thenAnswer(chamada -> {
                    lido[0] = chamada.<InputStream>getArgument(1).readAllBytes();
                    return new ResultadoDoEnvio(true, RESUMO, LEITURA);
                });

        mvc.perform(post("/api/v1/atendimentos").with(como(maria)).contentType(MediaType.APPLICATION_PDF)
                        .header("X-Nome-Arquivo", "C%3A%5Cpasta%5CConversa%20do%20cliente%20(1).pdf").content(PDF))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dados.criado").value(true))
                .andExpect(jsonPath("$.dados.atendimento.id").value(ID.toString()))
                .andExpect(jsonPath("$.dados.leitura.mensagens").value(14))
                .andExpect(jsonPath("$.dados.leitura.omitidos.chaveDoBot").value(1));

        assertThat(lido[0]).isEqualTo(PDF);
    }

    @Test
    void chamadoQueJaTemAtendimentoResponde200ComOExistente() throws Exception {
        when(acessos.liberado(coordenacao)).thenReturn(true);
        when(atendimentos.receberPdf(eq(coordenacao), any(), anyLong(), any())).thenReturn(new ResultadoDoEnvio(false, RESUMO, LEITURA));

        mvc.perform(post("/api/v1/atendimentos").with(como(coordenacao)).contentType(MediaType.APPLICATION_OCTET_STREAM).content(PDF))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.criado").value(false))
                .andExpect(jsonPath("$.dados.atendimento.id").value(ID.toString()));
    }

    @Test
    void pdfRecusadoNaLeituraVem422ComOCodigoEOProtocolo() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(atendimentos.receberPdf(eq(maria), any(), anyLong(), any())).thenThrow(LeituraDoPdfException.naoEDigisac());

        mvc.perform(post("/api/v1/atendimentos").with(como(maria)).contentType(MediaType.APPLICATION_PDF).content(PDF))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("PDF_NAO_E_DIGISAC"))
                .andExpect(jsonPath("$.protocolo").isNotEmpty());
    }

    @Test
    void outroFormatoDeEnvioERecusado() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);

        mvc.perform(post("/api/v1/atendimentos").with(como(maria)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.erros[0].codigo").value("FORMATO_NAO_SUPORTADO"));
        verify(atendimentos, never()).receberPdf(any(), any(), anyLong(), any());
    }

    @Test
    void listaEAbreOsProprios() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        when(atendimentos.listar(maria)).thenReturn(List.of(RESUMO));
        when(atendimentos.detalhe(maria, ID)).thenReturn(new AtendimentoView(ID, "20261009000001", "Loja Exemplo", null, null,
                "novo", Instant.parse("2026-10-09T15:00:00Z"), Instant.parse("2026-11-08T15:00:00Z"), null, null,
                Instant.parse("2026-10-10T12:00:00Z"), false, new Omitidos(1, 2), 1,
                List.of(new AtendimentoView.Item(1, "mensagem", "cliente", "Loja Exemplo", null, "Bom dia", List.of())),
                List.of()));

        mvc.perform(get("/api/v1/atendimentos").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados[0].id").value(ID.toString()));
        mvc.perform(get("/api/v1/atendimentos/" + ID).with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.itens[0].texto").value("Bom dia"));
    }

    @Test
    void atendimentoDeOutraPessoaVem404() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);
        RecursoNaoEncontradoException naoEncontrado = new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO",
                "Atendimento não encontrado. Ele pode ter sido apagado.");
        when(atendimentos.detalhe(maria, ID)).thenThrow(naoEncontrado);
        org.mockito.Mockito.doThrow(naoEncontrado).when(atendimentos).apagar(maria, ID);

        mvc.perform(get("/api/v1/atendimentos/" + ID).with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("ATENDIMENTO_NAO_ENCONTRADO"));
        mvc.perform(delete("/api/v1/atendimentos/" + ID).with(como(maria)))
                .andExpect(status().isNotFound());
    }

    @Test
    void apagaOProprio() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);

        mvc.perform(delete("/api/v1/atendimentos/" + ID).with(como(maria))).andExpect(status().isOk());

        verify(atendimentos).apagar(maria, ID);
    }

    @Test
    void idQueNaoEUuidNaoChegaAoServico() throws Exception {
        when(acessos.liberado(maria)).thenReturn(true);

        mvc.perform(get("/api/v1/atendimentos/abc").with(como(maria))).andExpect(status().is4xxClientError());

        verify(atendimentos, never()).detalhe(any(), any());
    }

    // --------------------------------------------------------------------------------------------- apoio

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
