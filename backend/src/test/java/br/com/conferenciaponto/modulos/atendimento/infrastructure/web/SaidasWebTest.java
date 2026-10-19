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
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.CopiaView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ModoDeCopia;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As rotas dos textos gerados: só com o gerador liberado, só os do dono, e as imagens e o .zip. */
@WebMvcTest(controllers = SaidasController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDoGerador.class, WebDoGerador.class})
class SaidasWebTest {

    private static final UUID ID = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final UUID SAIDA = UUID.fromString("2a7e1c3d-0000-4000-8000-0000000000bb");
    private static final UUID IMAGEM = UUID.fromString("2a7e1c3d-0000-4000-8000-0000000000cc");
    private static final String ROTA = "/api/v1/atendimentos/" + ID + "/saidas/" + SAIDA;

    @TempDir
    Path pasta;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarSaidas saidas;
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

    private static SaidaView view(String assunto) {
        return new SaidaView(SAIDA, "resumo", "Resumo do atendimento", 1, "pronta", null, assunto, 150,
                "<p data-fontes=\"M1\">texto</p>", false, List.of("1 fonte citada não existe no material e foi tirada."),
                Map.of("cliente", "Loja"), "Pedidos (VEN010)",
                List.of(new SaidaView.Imagem(IMAGEM, 1, "tela", true, true, "Imagem_01.png")), "gemini-3.8-flash", 1, 1000,
                200, Instant.parse("2026-10-12T15:00:00Z"), false, "", "", List.of());
    }

    @Test
    void confirmarEnviaOFormularioRevisado() throws Exception {
        maria = liberar(maria);
        TextoConfirmado.Formulario formulario = new TextoConfirmado.Formulario("Erro ao gravar", "erro", "Loja", "Maria",
                null, "Pedidos", "VEN010", "Não grava", null, null, "Acertar", "enviado_desenvolvimento", "OTRS 1");
        when(saidas.confirmar(maria, ID, SAIDA, formulario)).thenReturn(new GerenciarSaidas.ConfirmacaoView(view("x"),
                List.of("A imagem 2 não está mais no servidor (prazo de retenção) e não foi para a base.")));

        mvc.perform(post(ROTA + "/confirmar").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"titulo":"Erro ao gravar","tipo":"erro","cliente":"Loja","contato":"Maria","telaNome":"Pedidos",
                                 "telaCodigo":"VEN010","sintoma":"Não grava","resolucao":"Acertar",
                                 "situacao":"enviado_desenvolvimento","chamadoDev":"OTRS 1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.saida.id").value(SAIDA.toString()))
                .andExpect(jsonPath("$.dados.avisos[0]").exists());
    }

    @Test
    void semSessaoOuSemOGeradorLiberado() throws Exception {
        mvc.perform(get(ROTA)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        verify(saidas, never()).detalhe(any(), any(), any());
    }

    @Test
    void abreOTextoEditaOAssuntoOTextoEAsImagens() throws Exception {
        maria = liberar(maria);
        when(saidas.detalhe(maria, ID, SAIDA)).thenReturn(view("Digisac: Maria : erro"));
        when(saidas.salvarAssunto(maria, ID, SAIDA, "Digisac: Maria : outro")).thenReturn(view("Digisac: Maria : outro"));
        when(saidas.salvarTexto(maria, ID, SAIDA, "<p>novo</p>")).thenReturn(view("x"));
        when(saidas.ordenarImagens(maria, ID, SAIDA, List.of(IMAGEM))).thenReturn(view("x"));
        when(saidas.desfazerEdicao(maria, ID, SAIDA)).thenReturn(view("x"));

        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.assunto").value("Digisac: Maria : erro"))
                .andExpect(jsonPath("$.dados.html").value("<p data-fontes=\"M1\">texto</p>"))
                .andExpect(jsonPath("$.dados.imagens[0].arquivo").value("Imagem_01.png"))
                .andExpect(jsonPath("$.dados.avisos[0]").exists());
        mvc.perform(put(ROTA + "/assunto").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assunto\":\"Digisac: Maria : outro\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.assunto").value("Digisac: Maria : outro"));
        mvc.perform(put(ROTA + "/texto").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"html\":\"<p>novo</p>\"}"))
                .andExpect(status().isOk());
        mvc.perform(put(ROTA + "/imagens").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incluidas\":[\"" + IMAGEM + "\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROTA + "/desfazer").with(como(maria))).andExpect(status().isOk());
        when(saidas.salvarTela(maria, ID, SAIDA, "Pedidos (VEN010)")).thenReturn(view("x"));
        mvc.perform(put(ROTA + "/tela").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"telaFuncionalidade\":\"Pedidos (VEN010)\"}"))
                .andExpect(status().isOk());
        verify(saidas).salvarTela(maria, ID, SAIDA, "Pedidos (VEN010)");
    }

    @Test
    void ligaEDesligaAsMarcacoesDeUmaImagem() throws Exception {
        maria = liberar(maria);
        when(saidas.marcar(maria, ID, SAIDA, IMAGEM, true)).thenReturn(view("marcada"));
        when(saidas.marcar(maria, ID, SAIDA, IMAGEM, false)).thenReturn(view("sem marcação"));
        when(saidas.marcar(maria, ID, UUID.fromString("2a7e1c3d-0000-4000-8000-0000000000dd"), IMAGEM, true))
                .thenThrow(new RegraNegocioException("SEM_AREAS", "A análise desta imagem não apontou nenhuma área para marcar."));

        mvc.perform(put(ROTA + "/imagens/" + IMAGEM + "/marcacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ligada\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.assunto").value("marcada"))
                .andExpect(jsonPath("$.dados.imagens[0].comMarcacao").value(false))
                .andExpect(jsonPath("$.dados.imagens[0].podeMarcar").value(false));
        mvc.perform(put(ROTA + "/imagens/" + IMAGEM + "/marcacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ligada\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.assunto").value("sem marcação"));
        mvc.perform(put("/api/v1/atendimentos/" + ID + "/saidas/2a7e1c3d-0000-4000-8000-0000000000dd/imagens/" + IMAGEM
                        + "/marcacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON).content("{\"ligada\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("SEM_AREAS"));
        // retiraram os atendimentos da Maria: vale na hora
        when(usuarios.buscarPorLogin("maria")).thenReturn(Optional.of(maria.comAcesso(Acesso.usuario())));
        mvc.perform(put(ROTA + "/imagens/" + IMAGEM + "/marcacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ligada\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void gerarDeNovoECopiaNosTresModos() throws Exception {
        maria = liberar(maria);
        when(processamento.gerar(maria, ID, TipoDeSaida.RESUMO))
                .thenReturn(new ProgressoView(ID, "processando", null, 100, List.of()));
        when(saidas.copia(maria, ID, SAIDA, ModoDeCopia.COM_IMAGENS))
                .thenReturn(new CopiaView("com_imagens", "<p>x</p><p><img src=\"data:image/png;base64,AA\"></p>", "x", List.of()));

        mvc.perform(post("/api/v1/atendimentos/" + ID + "/saidas/resumo/gerar").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("processando"));
        mvc.perform(post("/api/v1/atendimentos/" + ID + "/saidas/soneto/gerar").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("SAIDA_DESCONHECIDA"));
        mvc.perform(get(ROTA + "/copia").param("modo", "com_imagens").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.html").value("<p>x</p><p><img src=\"data:image/png;base64,AA\"></p>"));
        mvc.perform(get(ROTA + "/copia").param("modo", "rtf").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODO_INVALIDO"));
    }

    @Test
    void aImagemEOZipComOsArquivosNumerados() throws Exception {
        maria = liberar(maria);
        Path png = Files.write(pasta.resolve("001_print.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        Path jpeg = Files.write(pasta.resolve("002_foto.jpeg"), new byte[]{(byte) 0xFF, (byte) 0xD8, 9});
        when(saidas.imagem(maria, ID, SAIDA, IMAGEM)).thenReturn(new GerenciarSaidas.ArquivoDaImagem(png, "image/png", "Imagem_01.png"));
        when(saidas.imagensParaOZip(maria, ID, SAIDA)).thenReturn(List.of(
                new GerenciarSaidas.ArquivoDaImagem(png, "image/png", "Imagem_01.png"),
                new GerenciarSaidas.ArquivoDaImagem(jpeg, "image/jpeg", "Imagem_02.jpeg")));

        mvc.perform(get(ROTA + "/imagens/" + IMAGEM).with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(Files.readAllBytes(png)));

        byte[] zip = mvc.perform(get(ROTA + "/imagens.zip").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"imagens.zip\""))
                .andReturn().getResponse().getContentAsByteArray();
        List<String> nomes = new ArrayList<>();
        try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = entrada.getNextEntry(); e != null; e = entrada.getNextEntry()) {
                nomes.add(e.getName() + ":" + entrada.readAllBytes().length);
            }
        }
        assertThat(nomes).containsExactly("Imagem_01.png:7", "Imagem_02.jpeg:3");
    }

    @Test
    void textoDeOutraPessoaVem404ESemTextoAvisa() throws Exception {
        maria = liberar(maria);
        when(saidas.detalhe(maria, ID, SAIDA))
                .thenThrow(new RecursoNaoEncontradoException("SAIDA_NAO_ENCONTRADA", "Texto não encontrado."));
        when(saidas.imagensParaOZip(maria, ID, SAIDA))
                .thenThrow(new RegraNegocioException("IMAGEM_APAGADA", "Os arquivos deste atendimento já foram apagados."));

        mvc.perform(get(ROTA).with(como(maria)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.erros[0].codigo").value("SAIDA_NAO_ENCONTRADA"));
        mvc.perform(get(ROTA + "/imagens.zip").with(como(maria)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].codigo").value("IMAGEM_APAGADA"));
    }
}
