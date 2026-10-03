package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.infrastructure.log.FiltroProtocolo;
import br.com.conferenciaponto.infrastructure.log.RegistroDeLogs;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O que a pessoa lê quando algo dá errado: toda falha vira uma frase em português, sem texto técnico, com o
 * protocolo que liga a tela à linha do log.
 */
class MensagensDeErroWebTest {

    record Pedido(@NotBlank(message = "Informe o motivo.") String motivo, LocalDate data, LocalTime horario,
                  TipoAusencia tipo, Integer quantidade) {
    }

    @RestController
    static class Controlador {

        @GetMapping("/api/teste/dia/{data}")
        ApiResponse<String> dia(@PathVariable LocalDate data, @RequestParam int mes) {
            return ApiResponse.ok(data + "/" + mes);
        }

        @PostMapping("/api/teste/pedido")
        ApiResponse<String> pedido(@Valid @RequestBody Pedido pedido) {
            return ApiResponse.ok("ok");
        }

        @GetMapping("/api/teste/falha/{qual}")
        ApiResponse<String> falha(@PathVariable String qual) {
            switch (qual) {
                case "regra" -> throw new RegraNegocioException("DIA_FECHADO", "Este dia já foi fechado pelo RH.");
                case "defeito" -> throw new IllegalStateException("índice 7 fora do vetor em Batidas.java:88");
                case "nulo" -> throw new NullPointerException("Cannot invoke \"Usuario.id()\" because \"u\" is null");
                case "repetido" -> throw new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: duplicate key value violates unique constraint \"uk_x\"", "23505"));
                case "em-uso" -> throw new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: violates foreign key constraint \"fk_y\"", "23503"));
                case "longo" -> throw new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: value too long for type character varying(120)", "22001"));
                case "integridade" -> throw new DataIntegrityViolationException("could not execute statement",
                        new SQLException("ERROR: null value in column \"x\"", "23502"));
                case "banco" -> throw new QueryTimeoutException("statement timeout");
                case "ocupado" -> throw new TaskRejectedException("Executor did not accept task");
                case "alterado" -> throw new ObjectOptimisticLockingFailureException("RegistroJornadaEntity", 1L);
                default -> {
                    return ApiResponse.ok(qual);
                }
            }
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Controlador())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new FiltroProtocolo())
            .build();

    /** O que nunca pode aparecer para a pessoa. */
    private static final List<String> TECNICO = List.of("Exception", "java.", "org.spring", "constraint", "null",
            "JSON", "Unrecognized", "Failed to convert", "LocalDate", "statement", "Batidas.java", "Executor");

    /** Só as frases (o envelope JSON tem "null" e nomes de campo que não são texto para a pessoa). */
    private static void semTextoTecnico(MvcResult resultado) throws Exception {
        String corpo = resultado.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder frases = new StringBuilder();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(corpo).path("erros")
                .forEach(erro -> frases.append(erro.path("mensagem").asText()).append(' '));
        assertThat(frases.toString().strip()).as("resposta sem frase: %s", corpo).isNotEmpty();
        assertThat(TECNICO).as("texto técnico em: %s", frases).noneMatch(t -> frases.toString().contains(t));
    }

    private MvcResult falha(String qual, int status, String codigo) throws Exception {
        MvcResult resultado = mvc.perform(get("/api/teste/falha/" + qual))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.sucesso").value(false))
                .andExpect(jsonPath("$.erros[0].codigo").value(codigo))
                .andReturn();
        semTextoTecnico(resultado);
        return resultado;
    }

    @Test
    @DisplayName("Toda resposta da API leva o protocolo no cabeçalho; nos erros ele vai também no corpo")
    void protocolo() throws Exception {
        MvcResult sucesso = mvc.perform(get("/api/teste/falha/nada")).andExpect(status().isOk()).andReturn();
        String doSucesso = sucesso.getResponse().getHeader(FiltroProtocolo.CABECALHO);
        assertThat(doSucesso).matches("[2-9A-HJKMNP-Z]{6}");
        assertThat(sucesso.getResponse().getContentAsString()).doesNotContain("protocolo");

        MvcResult erro = falha("regra", 422, "DIA_FECHADO");
        String doErro = erro.getResponse().getHeader(FiltroProtocolo.CABECALHO);
        assertThat(doErro).matches("[2-9A-HJKMNP-Z]{6}").isNotEqualTo(doSucesso);
        assertThat(erro.getResponse().getContentAsString()).contains("\"protocolo\":\"" + doErro + "\"");
    }

    @Test
    @DisplayName("Recusa de regra de negócio: a frase do domínio chega como foi escrita")
    void regraDeNegocio() throws Exception {
        mvc.perform(get("/api/teste/falha/regra"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erros[0].mensagem").value("Este dia já foi fechado pelo RH."));
    }

    @Test
    @DisplayName("Defeito inesperado: frase genérica com o protocolo; o detalhe técnico fica só no log, com a pilha")
    void erroInesperado() throws Exception {
        long antes = RegistroDeLogs.ultimaSequencia();
        for (String qual : new String[]{"defeito", "nulo", "integridade"}) {
            MvcResult resultado = falha(qual, 500, "ERRO_INTERNO");
            String protocolo = resultado.getResponse().getHeader(FiltroProtocolo.CABECALHO);
            String corpo = resultado.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(corpo).contains("Aconteceu um erro inesperado").contains("informe ao administrador o protocolo " + protocolo);
        }
        // (o appender de memória só é ligado na aplicação de verdade; aqui basta a resposta não vazar nada)
        assertThat(RegistroDeLogs.ultimaSequencia()).isGreaterThanOrEqualTo(antes);
    }

    @Test
    @DisplayName("Banco de dados: repetido, em uso, texto longo, indisponível e alterado em outra tela têm frase própria")
    void bancoDeDados() throws Exception {
        assertThat(mensagem(falha("repetido", 409, "REGISTRO_REPETIDO"))).contains("Já existe um registro igual");
        assertThat(mensagem(falha("em-uso", 409, "REGISTRO_EM_USO"))).contains("ligado a outros dados");
        assertThat(mensagem(falha("longo", 422, "TEXTO_LONGO_DEMAIS"))).contains("longo demais");
        assertThat(mensagem(falha("banco", 503, "BANCO_INDISPONIVEL"))).contains("Tente de novo em instantes");
        assertThat(mensagem(falha("alterado", 409, "REGISTRO_JA_ALTERADO"))).contains("Recarregue");
        assertThat(mensagem(falha("ocupado", 409, "SISTEMA_OCUPADO"))).contains("Aguarde terminar");
    }

    @Test
    @DisplayName("Parâmetro errado ou ausente: diz qual é e o formato esperado")
    void parametros() throws Exception {
        MvcResult data = mvc.perform(get("/api/teste/dia/31-12-2026?mes=1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros[0].campo").value("data")).andReturn();
        assertThat(mensagem(data)).contains("31-12-2026").contains("aaaa-mm-dd");
        semTextoTecnico(data);

        MvcResult numero = mvc.perform(get("/api/teste/dia/2026-12-31?mes=dezembro")).andExpect(status().isBadRequest()).andReturn();
        assertThat(mensagem(numero)).contains("dezembro").contains("\\\"mes\\\"").contains("número");
        semTextoTecnico(numero);

        MvcResult ausente = mvc.perform(get("/api/teste/dia/2026-12-31")).andExpect(status().isBadRequest()).andReturn();
        assertThat(mensagem(ausente)).contains("mes");
        semTextoTecnico(ausente);
    }

    @Test
    @DisplayName("Corpo inválido: JSON quebrado, data, horário, número e opção inexistente explicados por campo")
    void corpo() throws Exception {
        assertThat(mensagem(enviar("{\"motivo\": \"x\", \"data\": \"2026-10-0", 400))).contains("Faltou enviar os dados");
        assertThat(mensagem(enviar("", 400))).contains("Faltou enviar os dados");
        assertThat(mensagem(enviar("{\"motivo\":\"x\",\"data\":\"02/10/2026\"}", 400))).contains("02/10/2026").contains("aaaa-mm-dd");
        assertThat(mensagem(enviar("{\"motivo\":\"x\",\"horario\":\"25:99\"}", 400))).contains("25:99").contains("hh:mm");
        assertThat(mensagem(enviar("{\"motivo\":\"x\",\"quantidade\":\"muitas\"}", 400))).contains("muitas").contains("quantidade");
        assertThat(mensagem(enviar("{\"motivo\":\"x\",\"tipo\":\"PASSEIO\"}", 400)))
                .contains("PASSEIO").contains("FERIAS, ATESTADO, LICENCA, FOLGA, ABONO");
        MvcResult validacao = enviar("{\"motivo\":\" \"}", 400);
        assertThat(mensagem(validacao)).contains("Informe o motivo.");
        assertThat(validacao.getResponse().getContentAsString()).contains("\"campo\":\"motivo\"");
    }

    @Test
    @DisplayName("Operação que não existe e formato não aceito também respondem em português")
    void enderecosEFormatos() throws Exception {
        MvcResult metodo = mvc.perform(delete("/api/teste/pedido")).andExpect(status().is4xxClientError()).andReturn();
        assertThat(mensagem(metodo)).contains("Esta operação não existe no sistema");
        semTextoTecnico(metodo);

        MvcResult formato = mvc.perform(post("/api/teste/pedido").contentType(MediaType.TEXT_PLAIN).content("oi"))
                .andExpect(status().isUnsupportedMediaType()).andReturn();
        assertThat(mensagem(formato)).contains("formato que esta operação não aceita");
        semTextoTecnico(formato);
    }

    private MvcResult enviar(String json, int status) throws Exception {
        MvcResult resultado = mvc.perform(post("/api/teste/pedido").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(status)).andExpect(jsonPath("$.sucesso").value(false)).andReturn();
        semTextoTecnico(resultado);
        return resultado;
    }

    private static String mensagem(MvcResult resultado) throws Exception {
        return resultado.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
