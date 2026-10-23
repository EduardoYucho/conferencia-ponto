package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.usecase.ConferirMesUseCase;
import br.com.conferenciaponto.application.usecase.ConsultarBancoDeHorasUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;
import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView;
import br.com.conferenciaponto.application.view.ConferenciaDoMesView;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.security.RespostasSeguranca;
import br.com.conferenciaponto.infrastructure.security.SecurityConfig;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A tela Banco de horas (leitura para quem tem o ponto) e a conferência do mês (só com ponto.conferir). */
@WebMvcTest(controllers = {BancoDeHorasController.class, HorarioController.class})
@Import({SecurityConfig.class, RespostasSeguranca.class, AcessoUsuarios.class, BancoDeHorasWebTest.Relogio.class})
class BancoDeHorasWebTest {

    @TestConfiguration
    static class Relogio {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-21T21:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ConsultarBancoDeHorasUseCase consultar;
    @MockitoBean
    private ConferirMesUseCase conferir;
    @MockitoBean
    private GerenciarHorariosUseCase horarios;
    @MockitoBean
    private UsuarioRepository usuarios;

    private Usuario maria;
    private Usuario coordenacao;

    @BeforeEach
    void pessoas() {
        maria = usuario("maria", Acesso.usuario());
        coordenacao = usuario("coordenacao", Acesso.coordenador());
    }

    private Usuario usuario(String login, Acesso acesso) {
        Usuario u = new Usuario(UUID.nameUUIDFromBytes(login.getBytes()), login, login, "x", true, acesso, null);
        when(usuarios.buscarPorLogin(login)).thenReturn(Optional.of(u));
        return u;
    }

    private static RequestPostProcessor como(Usuario u) {
        return jwt().jwt(j -> j.subject(u.login())).authorities(u.acesso().permissoes().stream()
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    private BancoDeHorasMesView mes() {
        YearMonth outubro = YearMonth.of(2026, 10);
        HorarioTrabalho h = HorarioTrabalho.semanal(maria.id(), LocalDate.of(2026, 10, 1),
                GradeHoraria.ler("08:00-12:00 13:00-17:00"), 5, "maria", Instant.EPOCH).comCarga(42 * 3600);
        SaldoMensal resumo = new SaldoMensal(2026, 10, 13, 1, 345_600, 345_600, -2_880, -2_880, 0, -17_280);
        var compensacao = new BancoDeHorasMesView.Compensacao(42 * 3600, 40 * 3600, 7_200, 1_440, 5, true, 21, 12,
                -30_240, -17_280, 14_400, 14_400, 0, 15_840,
                List.of(new BancoDeHorasMesView.Bloco(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25))));
        var auditoria = new AuditoriaMesView(outubro, List.of(), resumo, List.of(), List.of(), List.of(), List.of());
        var conferencia = new ConferenciaDoMesView(null, false, resumo, List.of(), false);
        return new BancoDeHorasMesView(outubro, LocalDate.of(2026, 10, 21), h, compensacao, auditoria, Map.of(),
                Set.of(LocalDate.of(2026, 10, 10)), List.of(LocalDate.of(2026, 10, 9)),
                List.of(new AvisoLegal(AvisoLegal.Nivel.ALERTA, "SEM_FOLGA_7_DIAS", LocalDate.of(2026, 10, 5),
                        "De 05/10 a 11/10: 7 dias seguidos", "CLT, art. 67")), conferencia, Optional.empty(), List.of(),
                List.of(new AvisoLegal(AvisoLegal.Nivel.AVISO, "COMPENSACAO_COM_ACORDO", LocalDate.of(2026, 12, 16),
                        "Só com acordo", "EC 141/2026")));
    }

    @Test
    void aPessoaVeOProprioMesComACompensacao() throws Exception {
        when(consultar.mes(eq(maria.id()), eq(YearMonth.of(2026, 10)))).thenReturn(mes());

        mvc.perform(get("/api/v1/banco-de-horas?ano=2026&mes=10").with(como(maria)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.horario.cargaSemanalSegundos").value(42 * 3600))
                .andExpect(jsonPath("$.dados.horario.somaSemanalSegundos").value(40 * 3600))
                .andExpect(jsonPath("$.dados.horario.diferencaPorDiaSegundos").value(1_440))
                .andExpect(jsonPath("$.dados.horario.avisos[0].codigo").value("COMPENSACAO_COM_ACORDO"))
                .andExpect(jsonPath("$.dados.compensacao.faltaSegundos").value(15_840))
                .andExpect(jsonPath("$.dados.compensacao.finsDeSemanaRestantes[0].inicio").value("2026-10-24"))
                .andExpect(jsonPath("$.dados.resumo.segundosDaCarga").value(-17_280))
                .andExpect(jsonPath("$.dados.semRegistro[0]").value("2026-10-09"))
                .andExpect(jsonPath("$.dados.avisos[0].nivel").value("ALERTA"))
                .andExpect(jsonPath("$.dados.conferencia.podeConferir").value(false))
                .andExpect(jsonPath("$.dados.conferencia.vigente").doesNotExist());
    }

    @Test
    void soQuemConfereMarcaEReabreOMes() throws Exception {
        mvc.perform(post("/api/v1/banco-de-horas/conferencia?ano=2026&mes=9").with(como(maria)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/banco-de-horas/conferencia/reabrir?ano=2026&mes=9").with(como(maria)))
                .andExpect(status().isForbidden());
        verify(conferir, never()).conferir(any(), any(), any(), any());

        ConferenciaMes c = ConferenciaMes.nova(maria.id(), YearMonth.of(2026, 9), "coordenacao", "Ok",
                SaldoMensal.vazio(2026, 9, 0), Instant.parse("2026-10-21T21:00:00Z"));
        when(conferir.conferir(eq(maria.id()), eq(YearMonth.of(2026, 9)), eq("Ok"), eq("coordenacao"))).thenReturn(c);
        mvc.perform(post("/api/v1/banco-de-horas/conferencia?ano=2026&mes=9&usuario=maria").with(como(coordenacao))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"observacao\":\"Ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.conferidoPor").value("coordenacao"))
                .andExpect(jsonPath("$.dados.mes").value(9));

        when(conferir.reabrir(eq(maria.id()), eq(YearMonth.of(2026, 9)), eq("Faltou o atestado"), eq("coordenacao")))
                .thenReturn(c.reaberta("coordenacao", "Faltou o atestado", Instant.parse("2026-10-21T21:05:00Z")));
        mvc.perform(post("/api/v1/banco-de-horas/conferencia/reabrir?ano=2026&mes=9&usuario=maria").with(como(coordenacao))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Faltou o atestado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.reabertoPor").value("coordenacao"));
    }

    @Test
    void quemConfereVeOMesDeOutraPessoaComOBotao() throws Exception {
        when(usuarios.buscarPorLogin("maria")).thenReturn(Optional.of(maria));
        when(consultar.mes(eq(maria.id()), any())).thenReturn(mes());
        mvc.perform(get("/api/v1/banco-de-horas?ano=2026&mes=10&usuario=maria").with(como(coordenacao)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.conferencia.podeConferir").value(true));
        mvc.perform(get("/api/v1/banco-de-horas?ano=2026&mes=10&usuario=coordenacao").with(como(maria)))
                .andExpect(status().isForbidden());
    }

    @Test
    void avaliarOHorarioNaoSalvaEDevolveOsAvisos() throws Exception {
        HorarioTrabalho h = HorarioTrabalho.semanal(maria.id(), LocalDate.of(2026, 10, 21),
                GradeHoraria.ler("08:00-12:00 13:00-17:48"), 5, "avaliacao", Instant.EPOCH);
        when(horarios.avaliar(eq(maria.id()), any(), eq(5), any(), eq(42 * 3600))).thenReturn(
                new GerenciarHorariosUseCase.Vigencia(h.comCarga(42 * 3600), List.of(new AvisoLegal(AvisoLegal.Nivel.AVISO,
                        "SEMANA_ACIMA_DO_LIMITE", LocalDate.of(2026, 12, 16), "Passa de 42h", "EC 141/2026"))));

        mvc.perform(post("/api/v1/horarios/avaliacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vigenteDesde":"2026-10-21","toleranciaMinutos":5,"cargaSemanalSegundos":151200,
                                 "dias":{"SEG":"08:00-12:00 13:00-17:48"}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dados.cargaSemanalSegundos").value(42 * 3600))
                .andExpect(jsonPath("$.dados.cargaInformada").value(true))
                .andExpect(jsonPath("$.dados.avisos[0].codigo").value("SEMANA_ACIMA_DO_LIMITE"))
                .andExpect(jsonPath("$.dados.avisos[0].data").value("2026-12-16"));

        mvc.perform(post("/api/v1/horarios/avaliacao").with(como(coordenacao)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vigenteDesde\":\"2026-10-21\",\"toleranciaMinutos\":5,\"dias\":{}}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/horarios/avaliacao").with(como(maria)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vigenteDesde":"2026-10-21","toleranciaMinutos":5,"cargaSemanalSegundos":60,
                                 "dias":{"SEG":"08:00-12:00"}}"""))
                .andExpect(status().is4xxClientError());
    }
}
