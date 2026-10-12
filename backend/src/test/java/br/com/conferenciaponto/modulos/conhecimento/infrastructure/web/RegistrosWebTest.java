package br.com.conferenciaponto.modulos.conhecimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.FiltrosDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.PesquisarNaBase;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.RegistroEncontrado;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.ResultadoDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.EdicaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.GerenciarRegistros;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.RegistroView;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.pesquisa.Trecho;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.security.SegurancaDaBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** As rotas dos registros: só com a base liberada, e as respostas de pesquisa, conflito, permissão e imagem. */
@WebMvcTest(controllers = RegistrosController.class)
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, SegurancaDaBase.class, WebDaBase.class})
class RegistrosWebTest {

    private static final UUID ID = UUID.fromString("3b8f2d4e-0000-4000-8000-000000000001");
    private static final String ROTA = "/api/v1/conhecimento/registros";

    @TempDir
    Path pasta;

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarAcessosABase acessos;
    @MockitoBean
    private PesquisarNaBase pesquisa;
    @MockitoBean
    private GerenciarRegistros registros;

    private Usuario maria;
    private Usuario joao;

    @BeforeEach
    void cadastrar() {
        maria = usuario("maria");
        joao = usuario("joao");
        when(acessos.acessoDe(any())).thenAnswer(i -> {
            Usuario u = i.getArgument(0);
            return u.login().equals("maria") ? new AcessoABase(u.id(), true, false, "admin", null) : AcessoABase.semAcesso(u.id());
        });
    }

    private Usuario usuario(String login) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, login, "hash", true, Set.of(Perfil.ROLE_USER), null, null, false);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(usuario));
        return usuario;
    }

    private static RequestPostProcessor como(Usuario usuario) {
        return jwt().jwt(j -> j.subject(usuario.login())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RegistroView view(int versao) {
        return new RegistroView(ID, "gerador", "erro", "Erro", "Erro ao gravar", "Padaria", "Maria", "Vendas", "Pedidos",
                "VEN010", "Não grava", null, null, "Acertar", "<p>Texto</p>", "enviado_desenvolvimento",
                "Enviado ao desenvolvimento", null, null, false, null, null, versao, Instant.parse("2026-10-12T15:00:00Z"),
                Instant.parse("2026-10-12T15:00:00Z"), new RegistroView.Autor(UUID.randomUUID(), "Maria", "maria"),
                UUID.randomUUID(), UUID.randomUUID(), List.of(new RegistroView.Imagem(1, "tela", true, "Imagem_01.png")),
                List.of(), true, true);
    }

    @Test
    void semABaseLiberadaNaoEntra() throws Exception {
        mvc.perform(get(ROTA).with(como(joao)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("MODULO_NAO_LIBERADO"));
        mvc.perform(get(ROTA + "/" + ID)).andExpect(status().isUnauthorized());
        verify(pesquisa, never()).pesquisar(any(), any());
    }

    @Test
    void pesquisaComOsFiltrosDaTela() throws Exception {
        RegistroEncontrado achado = new RegistroEncontrado(ID, "erro", "Erro", "corrigido", "Corrigido",
                List.of(new Trecho.Parte("Erro ao gravar o ", false), new Trecho.Parte("pedido", true)), "Pedidos",
                List.of(new Trecho.Parte("VEN010", false)), "Padaria", "Vendas", "OTRS 1", "5.3", true, "Maria",
                Instant.parse("2026-10-12T15:00:00Z"), List.of());
        when(pesquisa.pesquisar(eq(maria), any())).thenReturn(new ResultadoDaPesquisa(List.of(achado), 1, 1, 1, 20, true));

        mvc.perform(get(ROTA).with(como(maria))
                        .param("q", "pedido").param("tipo", "erro").param("situacao", "corrigido").param("cliente", "pad")
                        .param("modulo", "vendas").param("de", "2026-10-01").param("ate", "2026-10-12")
                        .param("validadas", "true").param("pagina", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.total").value(1))
                .andExpect(jsonPath("$.dados.registros[0].titulo[1].texto").value("pedido"))
                .andExpect(jsonPath("$.dados.registros[0].titulo[1].destaque").value(true));

        ArgumentCaptor<FiltrosDaPesquisa> filtros = ArgumentCaptor.forClass(FiltrosDaPesquisa.class);
        verify(pesquisa).pesquisar(eq(maria), filtros.capture());
        assertThat(filtros.getValue()).isEqualTo(new FiltrosDaPesquisa("pedido", "erro", "corrigido", "pad", "vendas", null,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 12), true, 2));
    }

    @Test
    void dataInvalidaVoltaComMensagem() throws Exception {
        mvc.perform(get(ROTA).with(como(maria)).param("de", "12/10/2026"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editarComAVersaoEConflito() throws Exception {
        when(registros.editar(eq(maria), eq(ID), eq(3), any())).thenReturn(view(4));

        mvc.perform(put(ROTA + "/" + ID).with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versao\":3,\"titulo\":\"Erro ao gravar\",\"tipo\":\"erro\",\"telaCodigo\":\"VEN010\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.versao").value(4));
        ArgumentCaptor<EdicaoDoRegistro> edicao = ArgumentCaptor.forClass(EdicaoDoRegistro.class);
        verify(registros).editar(eq(maria), eq(ID), eq(3), edicao.capture());
        assertThat(edicao.getValue().telaCodigo()).isEqualTo("VEN010");

        when(registros.editar(eq(maria), eq(ID), eq(2), any())).thenThrow(new ConflitoException("REGISTRO_JA_ALTERADO",
                "Este registro foi alterado por outra pessoa; recarregue."));
        mvc.perform(put(ROTA + "/" + ID).with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versao\":2,\"titulo\":\"x\",\"tipo\":\"erro\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erros[0].codigo").value("REGISTRO_JA_ALTERADO"));
    }

    @Test
    void situacaoValidacaoEApagar() throws Exception {
        when(registros.mudarSituacao(maria, ID, 0, "corrigido", "OTRS 9", "5.3")).thenReturn(view(1));
        when(registros.validar(maria, ID, 1, true)).thenReturn(view(2));

        mvc.perform(put(ROTA + "/" + ID + "/situacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versao\":0,\"situacao\":\"corrigido\",\"chamadoDev\":\"OTRS 9\",\"versaoCorrecao\":\"5.3\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(ROTA + "/" + ID + "/validar-solucao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versao\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.versao").value(2));
        mvc.perform(delete(ROTA + "/" + ID).with(como(maria))).andExpect(status().isOk());
        verify(registros).apagar(maria, ID);
    }

    @Test
    void semPermissaoNoRegistroDa403() throws Exception {
        when(registros.validar(maria, ID, 1, true)).thenThrow(new AcessoNegadoException("SEM_PERMISSAO_NA_BASE",
                "Só o autor do registro ou quem cura a base pode validar a solução."));

        mvc.perform(post(ROTA + "/" + ID + "/validar-solucao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versao\":1,\"validada\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("SEM_PERMISSAO_NA_BASE"));
    }

    @Test
    void registroDaSaidaEImagem() throws Exception {
        UUID saida = UUID.randomUUID();
        when(registros.daSaida(saida)).thenReturn(ID);
        Path imagem = Files.write(pasta.resolve("Imagem_01.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        when(registros.imagem(maria, ID, 1)).thenReturn(new GerenciarRegistros.ArquivoDaImagem(imagem, "image/png",
                "Imagem_01.png"));

        mvc.perform(get(ROTA + "/da-saida/" + saida).with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.id").value(ID.toString()));
        mvc.perform(get(ROTA + "/" + ID + "/imagens/1").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
    }
}
