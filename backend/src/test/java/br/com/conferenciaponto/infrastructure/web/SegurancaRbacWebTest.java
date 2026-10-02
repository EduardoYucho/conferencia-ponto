package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.AjustarBatidasUseCase;
import br.com.conferenciaponto.application.usecase.AutenticarUsuarioUseCase;
import br.com.conferenciaponto.application.usecase.BaixarComprovanteUseCase;
import br.com.conferenciaponto.application.usecase.ConferirConciliacaoUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarAuditoriaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;
import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhasRemotas;
import br.com.conferenciaponto.application.view.EstadoPlanilhaView;
import br.com.conferenciaponto.domain.model.VinculoPlanilha;
import br.com.conferenciaponto.infrastructure.google.SincronizadorPlanilhas;
import br.com.conferenciaponto.application.usecase.ConsultarConciliacaoUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.application.usecase.ExcluirRegistroUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarFeriadosUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarLancamentosBancoUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarUsuariosUseCase;
import br.com.conferenciaponto.application.usecase.ImportarRelatorioRhUseCase;
import br.com.conferenciaponto.application.usecase.LancarRegistroManualUseCase;
import br.com.conferenciaponto.application.usecase.RegistrarBatidaUseCase;
import br.com.conferenciaponto.application.usecase.ResolverDivergenciaUseCase;
import br.com.conferenciaponto.application.view.ArquivoComprovanteView;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.application.view.MesJornadaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.application.view.SessaoView;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.HashSha256;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import br.com.conferenciaponto.infrastructure.importacao.GerenciadorMonitoresPdf;
import br.com.conferenciaponto.infrastructure.importacao.VerificadorPasta;
import br.com.conferenciaponto.infrastructure.security.JwtEmissorToken;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.security.SegurancaProperties;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regras do SecurityFilterChain (leitura para todos os perfis, escrita só para ADMIN/USER, cadastro de usuários e
 * feriados só ADMIN) e de AcessoUsuarios (cada um vê os seus dados; ADMIN e coordenação veem os de todos).
 */
@WebMvcTest(controllers = {JornadaController.class, ComprovanteController.class, AuditoriaController.class,
        AuthController.class, CicloBancoController.class, ConciliacaoController.class, LancamentoBancoController.class,
        FeriadoController.class, UsuarioController.class, PlanilhaController.class, IntegracaoGoogleController.class})
@Import({SecurityConfig.class, RespostasSeguranca.class, JwtEmissorToken.class, SegurancaRbacWebTest.Relogio.class,
        AcessoUsuarios.class})
class SegurancaRbacWebTest {

    private static final String MANUAL = """
            {"data":"2026-09-26","intervalos":[{"entrada":"09:00","saida":"12:30"}]}""";

    @TestConfiguration
    static class Relogio {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private SegurancaProperties segurancaProperties;

    @MockitoBean
    private ConsultarJornadaUseCase consultar;
    @MockitoBean
    private RegistrarBatidaUseCase registrarBatida;
    @MockitoBean
    private LancarRegistroManualUseCase lancarManual;
    @MockitoBean
    private ExcluirRegistroUseCase excluir;
    @MockitoBean
    private BaixarComprovanteUseCase baixar;
    @MockitoBean
    private ConsultarAuditoriaUseCase auditoria;
    @MockitoBean
    private AutenticarUsuarioUseCase autenticar;
    @MockitoBean
    private AjustarBatidasUseCase ajustar;
    @MockitoBean
    private GerenciarCicloBancoUseCase ciclos;
    @MockitoBean
    private ImportarRelatorioRhUseCase importarRh;
    @MockitoBean
    private ConsultarConciliacaoUseCase consultarConciliacao;
    @MockitoBean
    private ResolverDivergenciaUseCase resolverDivergencia;
    @MockitoBean
    private ConferirConciliacaoUseCase conferirConciliacao;
    @MockitoBean
    private GerenciarLancamentosBancoUseCase lancamentosBanco;
    @MockitoBean
    private GerenciarFeriadosUseCase gerenciarFeriados;
    @MockitoBean
    private GerenciarUsuariosUseCase gerenciarUsuarios;
    @MockitoBean
    private GerenciadorMonitoresPdf monitores;
    @MockitoBean
    private VerificadorPasta verificadorPasta;
    @MockitoBean
    private UsuarioRepository usuarios;
    @MockitoBean
    private GerenciarPlanilhaUseCase planilhas;
    @MockitoBean
    private SincronizadorPlanilhas sincronizadorPlanilhas;

    /** Usuários "do banco": coordenacao (VIEWER), maria (USER), provisoria (troca de senha); o resto é ADMIN. */
    private static Usuario doBanco(String login) {
        UUID id = idDe(login);
        return switch (login) {
            case "coordenacao" -> new Usuario(id, login, "Coordenação", "x", true, Set.of(Perfil.ROLE_VIEWER), null);
            case "maria" -> new Usuario(id, login, "Maria", "x", true, Set.of(Perfil.ROLE_USER), null);
            case "provisoria" -> new Usuario(id, login, "Nova", "x", true, Set.of(Perfil.ROLE_USER), null, null, true);
            default -> new Usuario(id, login, "Eduardo", "x", true, Set.of(Perfil.ROLE_ADMIN), null);
        };
    }

    private static UUID idDe(String login) {
        return UUID.nameUUIDFromBytes(login.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @BeforeEach
    void usuariosDoBanco() {
        when(usuarios.buscarPorLogin(any())).thenAnswer(inv -> Optional.of(doBanco(inv.getArgument(0))));
        when(usuarios.listar()).thenReturn(List.of(doBanco("eduardo"), doBanco("maria"), doBanco("coordenacao")));
    }

    private static SimpleGrantedAuthority perfil(Perfil p) {
        return new SimpleGrantedAuthority(p.name());
    }

    private void mesVazio() {
        when(consultar.mes(any(), any())).thenReturn(
                new MesJornadaView(YearMonth.of(2026, 9), List.of(), SaldoMensal.vazio(2026, 9, 0), List.of(), List.of()));
    }

    private RegistroJornadaView sabadoManual() {
        MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
        RegistroJornada r = RegistroJornada.novo(idDe("eduardo"), LocalDate.of(2026, 9, 26), TipoDia.FIM_DE_SEMANA);
        r.lancarManualmente(Batidas.deIntervalos(List.of(new Intervalo(LocalTime.of(9, 0), LocalTime.of(12, 30)))), motor);
        return RegistroJornadaView.de(r, motor);
    }

    /** Emite com o relógio real: o decoder confere a validade (exp) contra a hora atual, não a do teste. */
    private String tokenReal(Perfil perfil) {
        Usuario u = Usuario.novo(perfil == Perfil.ROLE_VIEWER ? "coordenacao" : "eduardo", "Teste", "x", Set.of(perfil));
        JwtEmissorToken emissor = new JwtEmissorToken(jwtEncoder, segurancaProperties, Clock.systemUTC());
        return "Bearer " + emissor.emitir(u).valor();
    }

    @Test
    @DisplayName("Sem token: 401 no envelope padrão")
    void semToken() throws Exception {
        mvc.perform(get("/api/v1/jornadas"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.sucesso").value(false))
                .andExpect(jsonPath("$.erros[0].codigo").value("NAO_AUTENTICADO"));
    }

    @Test
    @DisplayName("Lançar no banco e cadastrar feriado: VIEWER recebe 403, feriado só ADMIN; ADMIN lança (duração inválida = 400)")
    void lancamentoNoBancoEFeriado() throws Exception {
        String corpo = """
                {"data":"2026-09-26","duracao":"04:00","sentido":"DEBITO","descricao":"Compensação"}""";
        mvc.perform(post("/api/v1/lancamentos-banco").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/feriados").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"2026-06-04\",\"descricao\":\"Corpus Christi\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/feriados").with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":\"2026-06-04\",\"descricao\":\"Corpus Christi\"}"))
                .andExpect(status().isForbidden());
        verify(lancamentosBanco, never()).lancar(any(), any(), anyInt(), any(), any());

        when(lancamentosBanco.lancar(any(), any(), anyInt(), any(), any())).thenReturn(br.com.conferenciaponto.domain.model.LancamentoBanco
                .novo(idDe("eduardo"), LocalDate.of(2026, 9, 26), -14_400, "Compensação", "eduardo", Instant.now()));
        mvc.perform(post("/api/v1/lancamentos-banco").with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dados.segundos").value(-14_400));
        verify(lancamentosBanco).lancar(idDe("eduardo"), LocalDate.of(2026, 9, 26), -14_400, "Compensação", "eduardo");

        mvc.perform(post("/api/v1/lancamentos-banco").with(jwt().authorities(perfil(Perfil.ROLE_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(corpo.replace("04:00", "4h")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Front-end fora de /api: GET sem token é liberado; escrita e /api continuam protegidas")
    void frontendPublico() throws Exception {
        // 200 com o front-end no classpath (build com -Papp), 404 sem ele — nunca 401: a segurança liberou
        mvc.perform(get("/auditoria")).andExpect(r -> org.assertj.core.api.Assertions
                .assertThat(r.getResponse().getStatus()).isIn(200, 404));
        mvc.perform(get("/assets/index.js")).andExpect(status().isNotFound());
        mvc.perform(post("/auditoria")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/nao-existe")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("VIEWER lê, mas não escreve (403) — e o caso de uso nem é chamado")
    void viewerSomenteLeitura() throws Exception {
        mesVazio();
        mvc.perform(get("/api/v1/jornadas").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/jornadas/manual").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content(MANUAL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));
        mvc.perform(post("/api/v1/jornadas/batidas").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/jornadas/2026-09-26").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());

        verify(lancarManual, never()).executar(any(), any(), any());
        verify(excluir, never()).executar(any(), any());
    }

    @Test
    @DisplayName("USER e ADMIN escrevem")
    void usuarioEAdminEscrevem() throws Exception {
        when(lancarManual.executar(any(), any(), any())).thenReturn(sabadoManual());

        mvc.perform(post("/api/v1/jornadas/manual").with(jwt().authorities(perfil(Perfil.ROLE_USER)))
                        .contentType(MediaType.APPLICATION_JSON).content(MANUAL))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dados.saldoDiarioSegundos").value(210 * 60));
        mvc.perform(delete("/api/v1/jornadas/2026-09-26").with(jwt().authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk());
    }

    private static final String AJUSTE = """
            {"horarios": ["08:01:11", "12:00:03", "13:00", "17:42:57"],
             "justificativa": "Corrigido pelo RH: falha no relógio"}
            """;

    @Test
    @DisplayName("Ajuste manual: VIEWER não ajusta (403); ADMIN ajusta e o usuário vem do token, não do corpo")
    void ajusteManual() throws Exception {
        mvc.perform(put("/api/v1/jornadas/2026-06-26/batidas").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isForbidden());
        verify(ajustar, never()).executar(any(), any(), any(), any(), any());

        when(ajustar.executar(any(), any(), any(), any(), any())).thenReturn(sabadoManual());
        mvc.perform(put("/api/v1/jornadas/2026-06-26/batidas")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(AJUSTE))
                .andExpect(status().isOk());
        verify(ajustar).executar(idDe("eduardo"), LocalDate.of(2026, 6, 26),
                List.of(LocalTime.of(8, 1, 11), LocalTime.of(12, 0, 3), LocalTime.of(13, 0), LocalTime.of(17, 42, 57)),
                "Corrigido pelo RH: falha no relógio", "eduardo");

        mvc.perform(put("/api/v1/jornadas/2026-06-26/batidas").with(jwt().authorities(perfil(Perfil.ROLE_USER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"horarios\": [], \"justificativa\": \"\"}"))
                .andExpect(status().isBadRequest());

        when(ajustar.contexto(any(), any())).thenReturn(new AjustarBatidasUseCase.Contexto(List.of(), List.of()));
        mvc.perform(get("/api/v1/jornadas/2026-06-26/ajustes").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Download do PDF: VIEWER recebe o anexo com Content-Disposition: attachment")
    void downloadComoAnexo() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] pdf = "%PDF-1.4 comprovante".getBytes();
        ComprovanteArquivado c = new ComprovanteArquivado(id, UUID.randomUUID(), "2026/09/comprovante_" + id + ".pdf",
                TipoBatida.ENTRADA_1, Instant.now(), LocalDateTime.of(2026, 9, 28, 8, 2, 31),
                "comprovanteponto - 2026-09-28T080231.262.pdf", HashSha256.de(pdf), pdf.length);
        when(baixar.executar(any(), org.mockito.ArgumentMatchers.eq(id))).thenReturn(new ArquivoComprovanteView(c, pdf));

        mvc.perform(get("/api/comprovantes/{id}/download", id).with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment; filename=\"comprovante_2026-09-28_ENTRADA_1_080231.pdf\"")))
                .andExpect(content().bytes(pdf));
    }

    @Test
    @DisplayName("Login é público")
    void loginPublico() throws Exception {
        Usuario leitora = Usuario.novo("coordenacao", "Coordenação", "hash", Set.of(Perfil.ROLE_VIEWER));
        when(autenticar.autenticar(any(), any())).thenReturn(new SessaoView("token", Instant.now(), leitora));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"coordenacao\",\"senha\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.tipo").value("Bearer"))
                .andExpect(jsonPath("$.dados.usuario.podeEscrever").value(false))
                .andExpect(jsonPath("$.dados.usuario.senhaHash").doesNotExist());
    }

    @Test
    @DisplayName("JWT real emitido pela aplicação: claim 'roles' vira perfil; assinatura inválida = 401")
    void tokenReal() throws Exception {
        mesVazio();
        when(lancarManual.executar(any(), any(), any())).thenReturn(sabadoManual());
        String viewer = tokenReal(Perfil.ROLE_VIEWER);

        mvc.perform(get("/api/v1/jornadas").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/jornadas/batidas").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/jornadas/manual").header(HttpHeaders.AUTHORIZATION, tokenReal(Perfil.ROLE_ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(MANUAL))
                .andExpect(status().is2xxSuccessful());

        String adulterado = viewer.substring(0, viewer.length() - 4) + "AAAA";
        mvc.perform(get("/api/v1/jornadas").header(HttpHeaders.AUTHORIZATION, adulterado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Fechar banco de horas (também em /api/ciclos/fechar) e enviar relatório do RH: só quem escreve")
    void cicloEConciliacao() throws Exception {
        mvc.perform(post("/api/ciclos/fechar").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/conciliacoes").file(new MockMultipartFile("arquivo", "rh.pdf",
                        "application/pdf", new byte[]{1})).with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());
        verify(ciclos, never()).fechar(any(), any(), any(), any());
        verify(importarRh, never()).receber(any(), any(), any(), any());

        when(consultarConciliacao.resumo(any())).thenReturn(new ConciliacaoResumoView(List.of(), Map.of(), Map.of()));
        mvc.perform(get("/api/v1/conciliacoes/resumo").with(jwt().authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.tipos.length()").value(8))
                .andExpect(jsonPath("$.dados.pendentes").value(0));
    }

    @Test
    @DisplayName("Envio do relatório do RH: multipart com o campo \"arquivo\" → 202; sem arquivo → 400")
    void envioRelatorioRh() throws Exception {
        RelatorioRh r = new RelatorioRh(UUID.randomUUID(), idDe("eduardo"), "rh.pdf", "a".repeat(64), "000042 - Teste",
                LocalDateTime.of(2026, 9, 14, 16, 16, 58), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                null, null, 14_262, 31, StatusRelatorioRh.PROCESSANDO, null, 0, Instant.now(), "eduardo", null);
        when(importarRh.receber(any(), any(), any(), any())).thenReturn(r);

        mvc.perform(multipart("/api/v1/conciliacoes").file(new MockMultipartFile("arquivo", "rh.pdf",
                        "application/pdf", new byte[]{1})).with(jwt().authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.dados.status").value("PROCESSANDO"))
                .andExpect(jsonPath("$.dados.ultimoDiaConferido").value("2026-08-31"));
        mvc.perform(multipart("/api/v1/conciliacoes").with(jwt().authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].codigo").value("ARQUIVO_OBRIGATORIO"));
    }

    @Test
    @DisplayName("Cada um vê os próprios dados; ADMIN e coordenação escolhem o usuário (?usuario=); ninguém altera os de outro")
    void dadosDeCadaUsuario() throws Exception {
        mesVazio();
        mvc.perform(get("/api/v1/jornadas").with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isOk());
        verify(consultar).mes(org.mockito.ArgumentMatchers.eq(idDe("maria")), any());

        mvc.perform(get("/api/v1/jornadas").param("usuario", "eduardo")
                        .with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ACESSO_NEGADO"));

        mvc.perform(get("/api/v1/jornadas").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/jornadas").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk());
        verify(consultar, org.mockito.Mockito.times(3)).mes(org.mockito.ArgumentMatchers.eq(idDe("maria")), any());

        mvc.perform(post("/api/v1/jornadas/batidas").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("ALTERAR_DADOS_DE_OUTRO"));
        verify(registrarBatida, never()).executar(any(), any(), any());
    }

    @Test
    @DisplayName("Senha provisória: nada de dados de ponto até trocar a senha")
    void senhaProvisoria() throws Exception {
        mvc.perform(get("/api/v1/jornadas").with(jwt().jwt(j -> j.subject("provisoria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.erros[0].codigo").value("TROCAR_SENHA"));
    }

    @Test
    @DisplayName("Cadastro de usuários: só ADMIN; a lista de titulares é liberada para a coordenação")
    void cadastroDeUsuarios() throws Exception {
        when(gerenciarUsuarios.listar()).thenReturn(List.of(doBanco("eduardo"), doBanco("maria")));
        when(gerenciarUsuarios.titulares()).thenReturn(List.of(doBanco("eduardo"), doBanco("maria")));
        when(monitores.estado(any())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/usuarios").with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/usuarios").with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/usuarios").with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"joao\",\"nome\":\"João\",\"perfil\":\"ROLE_ADMIN\",\"senhaProvisoria\":\"12345678\"}"))
                .andExpect(status().isForbidden());
        verify(gerenciarUsuarios, never()).criar(any(), any(), any(), any(), any(), any());

        mvc.perform(get("/api/v1/usuarios").with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.length()").value(2))
                .andExpect(jsonPath("$.dados[1].perfil").value("ROLE_USER"))
                .andExpect(jsonPath("$.dados[1].situacaoMonitor").value("SEM_PASTA"));

        mvc.perform(get("/api/v1/usuarios/titulares")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.length()").value(2));
        mvc.perform(get("/api/v1/usuarios/titulares")
                        .with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.length()").value(1))
                .andExpect(jsonPath("$.dados[0].login").value("maria"));
    }

    @Test
    @DisplayName("Planilha: quem consulta os dados baixa o Excel e vê o link; só a própria pessoa ou o ADMIN vinculam")
    void planilhaDeConferencia() throws Exception {
        String id = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
        VinculoPlanilha vinculo = VinculoPlanilha.novo(idDe("maria"), id, "Ponto da Maria", "maria", Instant.parse("2026-09-29T12:00:00Z"))
                .sincronizada(null, Instant.parse("2026-09-29T12:00:05Z"));
        EstadoPlanilhaView estado = new EstadoPlanilhaView("planilhas@projeto.iam.gserviceaccount.com", vinculo);
        when(planilhas.estado(any())).thenReturn(estado);
        when(planilhas.vincular(any(), any(), any())).thenReturn(estado);
        when(planilhas.montar(any())).thenReturn(new PlanilhaConferencia("Maria", "maria", List.of(new Aba(Aba.ID_RESUMO,
                "Resumo", List.of(List.of(Celula.texto("Conferência de ponto · Maria"))), List.of(190), 0)), Instant.EPOCH));

        // coordenação: consulta o link e baixa o arquivo da pessoa escolhida
        mvc.perform(get("/api/v1/planilha").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("SINCRONIZADA"))
                .andExpect(jsonPath("$.dados.url").value("https://docs.google.com/spreadsheets/d/" + id + "/edit"))
                .andExpect(jsonPath("$.dados.emailServico").value("planilhas@projeto.iam.gserviceaccount.com"));
        verify(planilhas).estado(idDe("maria"));
        mvc.perform(get("/api/v1/planilha/exportar").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"conferencia-ponto-maria-2026-09-29.xlsx\""))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray()).startsWith('P', 'K'));
        // ... mas não vincula nem manda gravar
        mvc.perform(put("/api/v1/planilha").param("usuario", "maria").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"" + id + "\"}")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/planilha/sincronizar")
                        .with(jwt().jwt(j -> j.subject("coordenacao")).authorities(perfil(Perfil.ROLE_VIEWER))))
                .andExpect(status().isForbidden());

        // a própria pessoa vincula a dela; a de outra pessoa, não
        mvc.perform(put("/api/v1/planilha").contentType(MediaType.APPLICATION_JSON).content("{\"link\":\"" + id + "\"}")
                        .with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.titulo").value("Ponto da Maria"));
        verify(planilhas).vincular(idDe("maria"), id, "maria");
        mvc.perform(put("/api/v1/planilha").param("usuario", "eduardo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"" + id + "\"}")
                        .with(jwt().jwt(j -> j.subject("maria")).authorities(perfil(Perfil.ROLE_USER))))
                .andExpect(status().isForbidden());

        // o administrador vincula, atualiza e desconecta a planilha de outra pessoa
        mvc.perform(put("/api/v1/planilha").param("usuario", "maria").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"link\":\"" + id + "\"}")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk());
        verify(planilhas).vincular(idDe("maria"), id, "eduardo");
        when(planilhas.sincronizarAgora(any())).thenReturn(estado);
        mvc.perform(post("/api/v1/planilha/sincronizar").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk());
        verify(planilhas).sincronizarAgora(idDe("maria"));
        when(planilhas.desvincular(any())).thenReturn(new EstadoPlanilhaView("planilhas@projeto.iam.gserviceaccount.com", null));
        mvc.perform(delete("/api/v1/planilha").param("usuario", "maria")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.situacao").value("SEM_PLANILHA"));
    }

    @Test
    @DisplayName("Conta de serviço do Google: só o ADMIN consulta, envia a chave ou desliga; a chave nunca volta na resposta")
    void integracaoComOGoogle() throws Exception {
        var integracao = new GerenciarPlanilhaUseCase.Integracao(
                new PlanilhasRemotas.Conta("planilhas@projeto.iam.gserviceaccount.com", "projeto"), 2);
        when(planilhas.integracao()).thenReturn(integracao);
        when(planilhas.configurar(any())).thenReturn(integracao);

        for (Perfil p : List.of(Perfil.ROLE_USER, Perfil.ROLE_VIEWER)) {
            String login = p == Perfil.ROLE_USER ? "maria" : "coordenacao";
            mvc.perform(get("/api/v1/integracoes/google").with(jwt().jwt(j -> j.subject(login)).authorities(perfil(p))))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/integracoes/google").contentType(MediaType.APPLICATION_JSON).content("{\"chave\":\"{}\"}")
                            .with(jwt().jwt(j -> j.subject(login)).authorities(perfil(p))))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/v1/integracoes/google").with(jwt().jwt(j -> j.subject(login)).authorities(perfil(p))))
                    .andExpect(status().isForbidden());
        }
        verify(planilhas, never()).configurar(any());
        verify(planilhas, never()).desconfigurar();

        mvc.perform(put("/api/v1/integracoes/google").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chave\":\"{\\\"type\\\":\\\"service_account\\\",\\\"private_key\\\":\\\"SEGREDO\\\"}\"}")
                        .with(jwt().jwt(j -> j.subject("eduardo")).authorities(perfil(Perfil.ROLE_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.configurada").value(true))
                .andExpect(jsonPath("$.dados.email").value("planilhas@projeto.iam.gserviceaccount.com"))
                .andExpect(jsonPath("$.dados.planilhas").value(2))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SEGREDO"))));
        verify(planilhas).configurar("{\"type\":\"service_account\",\"private_key\":\"SEGREDO\"}");
        verify(sincronizadorPlanilhas).agendarTodos();
    }
}
