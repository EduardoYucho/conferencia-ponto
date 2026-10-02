package br.com.conferenciaponto.infrastructure.google;

import br.com.conferenciaponto.application.planilha.Aba;
import br.com.conferenciaponto.application.planilha.Celula;
import br.com.conferenciaponto.application.planilha.Estilo;
import br.com.conferenciaponto.application.planilha.PlanilhaConferencia;
import br.com.conferenciaponto.application.planilha.PlanilhaRemotaException;
import br.com.conferenciaponto.application.planilha.PlanilhasRemotas;
import br.com.conferenciaponto.infrastructure.google.GoogleDeMentira.AbaFalsa;
import br.com.conferenciaponto.infrastructure.google.GoogleDeMentira.PlanilhaFalsa;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PlanilhasGoogleTest {

    private static final String ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";
    private static final int JUNHO = 202606;
    private static final int JULHO = 202607;

    @TempDir
    Path pasta;

    private final ObjectMapper json = new ObjectMapper();
    private GoogleDeMentira google;
    private CredencialGoogle credencial;
    private GoogleSheetsApi api;
    private PlanilhasGoogle planilhas;

    @BeforeEach
    void subir() throws Exception {
        google = new GoogleDeMentira();
        GoogleProperties properties = new GoogleProperties(pasta.resolve("google/conta-de-servico.json").toString(),
                google.url() + "/token", google.url(), Duration.ofSeconds(4), Duration.ofSeconds(10));
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T17:30:00Z"), ZoneOffset.UTC);
        credencial = new CredencialGoogle(properties, json);
        api = new GoogleSheetsApi(properties, credencial, json, clock);
        planilhas = new PlanilhasGoogle(api, credencial, json);
    }

    @AfterEach
    void derrubar() {
        google.close();
    }

    private static Aba mes(int id, String titulo, long saldo) {
        return new Aba(id, titulo, List.of(
                List.of(Celula.texto("Data", Estilo.CABECALHO), Celula.texto("Entrada 1", Estilo.CABECALHO),
                        Celula.texto("Saldo do dia", Estilo.CABECALHO)),
                List.of(Celula.texto("Total do mês", Estilo.TOTAL), Celula.vazia(Estilo.TOTAL),
                        Celula.formulaDuracao("SUM(C3:C3)", saldo, Estilo.TOTAL.corDoSaldo(saldo))),
                List.of(Celula.data(LocalDate.of(id / 100, id % 100, 1), Estilo.CENTRO),
                        Celula.hora(LocalTime.of(8, 5, 23), Estilo.CENTRO), Celula.duracao(saldo, Estilo.NORMAL))),
                List.of(88, 84, 92), 2);
    }

    private static Aba resumo(Aba... meses) {
        List<List<Celula>> linhas = new ArrayList<>();
        linhas.add(List.of(Celula.texto("Conferência de ponto · Eduardo", Estilo.TITULO), Celula.VAZIA));
        for (Aba m : meses) {
            linhas.add(List.of(Celula.texto(m.titulo()), Celula.formulaDuracao(m.refExterna(1, 2), 0, Estilo.NORMAL)));
        }
        return new Aba(Aba.ID_RESUMO, "Resumo", linhas, List.of(190, 118), 0);
    }

    private static PlanilhaConferencia planilha(Aba... meses) {
        List<Aba> abas = new ArrayList<>();
        abas.add(resumo(meses));
        abas.addAll(List.of(meses));
        return new PlanilhaConferencia("Eduardo", "eduardo", abas, Instant.EPOCH);
    }

    private PlanilhaFalsa configurada() {
        planilhas.configurar(google.chaveJson());
        return google.criarPlanilha(ID, "Ponto do Eduardo");
    }

    @Test
    @DisplayName("Chave da conta de serviço: conferida com o Google, gravada no arquivo e apagada ao desligar")
    void chave() throws Exception {
        assertThat(planilhas.conta()).isEmpty();

        PlanilhasRemotas.Conta conta = planilhas.configurar(google.chaveJson());

        assertThat(conta.email()).isEqualTo("planilhas@projeto-de-teste.iam.gserviceaccount.com");
        assertThat(conta.projeto()).isEqualTo("projeto-de-teste");
        assertThat(planilhas.conta()).contains(conta);
        Path arquivo = pasta.resolve("google/conta-de-servico.json");
        assertThat(arquivo).exists();
        // o pedido de token leva um JWT assinado com a chave (o "Google" de teste confere a assinatura)
        JsonNode jwt = google.ultimaAssercao();
        assertThat(jwt.path("iss").asText()).isEqualTo(conta.email());
        assertThat(jwt.path("scope").asText()).isEqualTo("https://www.googleapis.com/auth/spreadsheets");
        assertThat(jwt.path("aud").asText()).isEqualTo(google.url() + "/token");
        assertThat(jwt.path("exp").asLong() - jwt.path("iat").asLong()).isEqualTo(3600);

        // outra instância (o sistema reiniciado) lê a chave do arquivo
        CredencialGoogle relida = new CredencialGoogle(new GoogleProperties(arquivo.toString(), "x", "x",
                Duration.ofSeconds(4), Duration.ofSeconds(10)), json);
        assertThat(relida.chave()).map(CredencialGoogle.Chave::email).contains(conta.email());

        planilhas.desconfigurar();
        assertThat(planilhas.conta()).isEmpty();
        assertThat(arquivo).doesNotExist();
    }

    @Test
    @DisplayName("Chave inválida ou recusada pelo Google não é gravada")
    void chaveRecusada() {
        assertThatThrownBy(() -> planilhas.configurar("isto não é json"))
                .isInstanceOfSatisfying(PlanilhaRemotaException.class, e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_CHAVE_INVALIDA"));
        assertThatThrownBy(() -> planilhas.configurar("{\"type\":\"authorized_user\",\"client_email\":\"a@b\"}"))
                .hasMessageContaining("não é uma chave de conta de serviço");
        assertThatThrownBy(() -> planilhas.configurar("{\"type\":\"service_account\",\"client_email\":\"a@b\",\"private_key\":\"xx\"}"))
                .hasMessageContaining("chave privada");

        google.recusarChave(true);
        PlanilhaRemotaException recusa = catchThrowableOfType(PlanilhaRemotaException.class,
                () -> planilhas.configurar(google.chaveJson()));
        assertThat(recusa.getCodigo()).isEqualTo("GOOGLE_CHAVE_RECUSADA");
        assertThat(recusa.isTransitoria()).isFalse();
        assertThat(recusa).hasMessageContaining("Invalid JWT Signature");
        assertThat(planilhas.conta()).isEmpty();
        assertThat(pasta.resolve("google/conta-de-servico.json")).doesNotExist();
    }

    @Test
    @DisplayName("Primeira gravação: cria o resumo e os meses, protege as abas com aviso e remove a aba vazia da planilha nova")
    void primeiraGravacao() {
        PlanilhaFalsa remota = configurada();

        String titulo = planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 600), mes(JUNHO, "Jun 2026", -5_400)), null);

        assertThat(titulo).isEqualTo("Ponto do Eduardo");
        assertThat(planilhas.verificar(ID)).isEqualTo("Ponto do Eduardo");
        assertThat(remota.titulos()).containsExactly("Resumo", "Jul 2026", "Jun 2026");
        assertThat(google.tokensEmitidos()).as("um token para todas as chamadas").isEqualTo(1);

        AbaFalsa junho = remota.aba(JUNHO);
        assertThat(junho.protegida).isTrue();
        assertThat(junho.congeladas).isEqualTo(2);
        assertThat(junho.colunas).isEqualTo(3);
        assertThat(junho.linhas).isEqualTo(5);
        assertThat(junho.valor("A1")).isEqualTo("Data");
        // data, horário e duração como números; total em fórmula
        assertThat(junho.valor("A3")).isEqualTo((double) LocalDate.of(2026, 6, 1).toEpochDay() + 25_569);
        assertThat(junho.formato("A3").path("numberFormat").path("pattern").asText()).isEqualTo("dd/mm/yyyy");
        assertThat(junho.valor("B3")).isEqualTo(LocalTime.of(8, 5, 23).toSecondOfDay() / 86_400.0);
        assertThat(junho.formato("B3").path("numberFormat").path("pattern").asText()).isEqualTo("hh:mm:ss");
        assertThat(junho.valor("C3")).isEqualTo(-5_400 / 86_400.0);
        assertThat(junho.formato("C3").path("numberFormat").path("pattern").asText()).isEqualTo("[h]:mm:ss");
        assertThat(junho.valor("C2")).isEqualTo("=SUM(C3:C3)");
        assertThat(junho.formato("C2").path("textFormat").path("bold").asBoolean()).isTrue();
        assertThat(junho.formato("A1").path("backgroundColorStyle").path("rgbColor").has("red")).isTrue();
        // o resumo aponta para os totais das abas dos meses, que foram gravadas antes dele
        assertThat(remota.aba(Aba.ID_RESUMO).valor("B3")).isEqualTo("='Jun 2026'!C2");
        List<String> tipos = google.tipos();
        assertThat(tipos.subList(0, 6)).containsExactly("addSheet", "addProtectedRange", "addSheet", "addProtectedRange",
                "addSheet", "addProtectedRange");
        assertThat(tipos).last().isEqualTo("deleteSheet");
        List<Integer> gravadas = google.requisicoes().stream().filter(r -> r.has("updateCells"))
                .map(r -> r.path("updateCells").path("range").path("sheetId").asInt()).toList();
        assertThat(gravadas).containsExactly(JULHO, JUNHO, Aba.ID_RESUMO);
    }

    @Test
    @DisplayName("Gravação parcial: só as abas pedidas e o resumo; as outras abas e as da pessoa ficam intactas")
    void gravacaoParcial() {
        PlanilhaFalsa remota = configurada();
        planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 600), mes(JUNHO, "Jun 2026", -5_400)), null);
        google.esquecerRequisicoes();

        planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 900), mes(JUNHO, "Jun 2026", -9_999)),
                Set.of(JULHO, Aba.ID_RESUMO));

        assertThat(remota.aba(JULHO).valor("C3")).isEqualTo(900 / 86_400.0);
        assertThat(remota.aba(JUNHO).valor("C3")).as("junho não foi pedido").isEqualTo(-5_400 / 86_400.0);
        assertThat(google.tipos()).doesNotContain("addSheet", "deleteSheet", "addProtectedRange");
        assertThat(google.chamadas()).as("metadados + estrutura + conteúdo").isEqualTo(3);
        assertThat(google.tokensEmitidos()).isEqualTo(1);
    }

    @Test
    @DisplayName("Mês novo entra mesmo na gravação parcial; mês que deixou de existir só sai na gravação completa")
    void mesesNovosERemovidos() {
        PlanilhaFalsa remota = configurada();
        planilhas.publicar(ID, planilha(mes(JUNHO, "Jun 2026", 0)), null);

        planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 600), mes(JUNHO, "Jun 2026", 0)), Set.of(Aba.ID_RESUMO));
        assertThat(remota.titulos()).as("a aba nova entra logo depois do resumo").containsExactly("Resumo", "Jul 2026", "Jun 2026");

        planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 600)), Set.of(Aba.ID_RESUMO));
        assertThat(remota.titulos()).contains("Jun 2026");

        planilhas.publicar(ID, planilha(mes(JULHO, "Jul 2026", 600)), null);
        assertThat(remota.titulos()).containsExactly("Resumo", "Jul 2026");
    }

    @Test
    @DisplayName("Abas que não são do sistema ficam como estão; aba renomeada pela pessoa volta ao nome")
    void abasDaPessoa() {
        PlanilhaFalsa remota = configurada();
        remota.abas.get(0).celulas.put("A1", json.createObjectNode().set("userEnteredValue",
                json.createObjectNode().put("stringValue", "minhas anotações")));

        planilhas.publicar(ID, planilha(mes(JUNHO, "Jun 2026", 0)), null);
        assertThat(remota.titulos()).as("a aba com conteúdo não é apagada").containsExactly("Resumo", "Jun 2026", "Página1");
        assertThat(remota.aba(0).valor("A1")).isEqualTo("minhas anotações");

        remota.aba(JUNHO).titulo = "Junho (renomeada)";
        remota.aba(JUNHO).celulas.put("Z99", json.createObjectNode().set("userEnteredValue",
                json.createObjectNode().put("stringValue", "rabisco")));
        planilhas.publicar(ID, planilha(mes(JUNHO, "Jun 2026", 0)), null);
        assertThat(remota.aba(JUNHO).titulo).isEqualTo("Jun 2026");
        assertThat(remota.aba(JUNHO).valor("Z99")).as("a gravação troca todo o conteúdo da aba").isNull();
    }

    @Test
    @DisplayName("Aba de outra origem com o nome de uma aba do sistema: nada é gravado e a mensagem diz o que fazer")
    void conflitoDeNome() {
        PlanilhaFalsa remota = configurada();
        remota.abas.get(0).titulo = "jun 2026";

        PlanilhaRemotaException e = catchThrowableOfType(PlanilhaRemotaException.class,
                () -> planilhas.publicar(ID, planilha(mes(JUNHO, "Jun 2026", 0)), null));

        assertThat(e.getCodigo()).isEqualTo("GOOGLE_ABA_EM_CONFLITO");
        assertThat(e).hasMessageContaining("\"Jun 2026\"");
        assertThat(google.requisicoes()).isEmpty();
        assertThat(remota.titulos()).containsExactly("jun 2026");
    }

    @Test
    @DisplayName("Recusas do Google viram mensagens com o que fazer; limite e erro do serviço valem nova tentativa")
    void recusas() {
        configurada();

        google.falharCom(403, "PERMISSION_DENIED", "The caller does not have permission", null);
        PlanilhaRemotaException semPermissao = catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID));
        assertThat(semPermissao.getCodigo()).isEqualTo("GOOGLE_SEM_PERMISSAO");
        assertThat(semPermissao.isTransitoria()).isFalse();
        assertThat(semPermissao).hasMessageContaining("planilhas@projeto-de-teste.iam.gserviceaccount.com").hasMessageContaining("Editor");

        google.falharCom(403, "PERMISSION_DENIED", "Google Sheets API has not been used in project 123 before or it is disabled.",
                "SERVICE_DISABLED");
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_API_DESATIVADA"))
                .hasMessageContaining("projeto-de-teste");

        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar("id-que-nao-existe-000000000")))
                .satisfies(e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_PLANILHA_NAO_ENCONTRADA"));

        google.falharCom(400, "FAILED_PRECONDITION", "This operation is not supported for this document", null);
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_ARQUIVO_NAO_E_PLANILHA"));

        google.falharCom(429, "RESOURCE_EXHAUSTED", "Quota exceeded", "RATE_LIMIT_EXCEEDED");
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.isTransitoria()).isTrue());
        google.falharCom(503, "UNAVAILABLE", "The service is currently unavailable.", null);
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.isTransitoria()).isTrue());

        google.falharCom(400, "INVALID_ARGUMENT", "Invalid requests[0].updateCells: algo errado", null);
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_RECUSOU"))
                .hasMessageContaining("algo errado");
    }

    @Test
    @DisplayName("Token recusado antes de vencer: pede outro e repete a chamada; sem conta configurada, avisa")
    void tokenESemConta() {
        PlanilhaFalsa remota = configurada();
        assertThat(planilhas.verificar(ID)).isEqualTo(remota.titulo);
        assertThat(google.tokensEmitidos()).isEqualTo(1);

        google.invalidarToken();
        assertThat(planilhas.verificar(ID)).isEqualTo(remota.titulo);
        assertThat(google.tokensEmitidos()).isEqualTo(2);

        planilhas.desconfigurar();
        assertThat(catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID)))
                .satisfies(e -> assertThat(e.getCodigo()).isEqualTo("GOOGLE_SEM_CONTA"));
    }

    @Test
    @DisplayName("Google fora do ar (sem conexão): falha passageira")
    void semConexao() {
        configurada();
        planilhas.verificar(ID);
        google.close();

        PlanilhaRemotaException e = catchThrowableOfType(PlanilhaRemotaException.class, () -> planilhas.verificar(ID));

        assertThat(e.getCodigo()).isEqualTo("GOOGLE_SEM_CONEXAO");
        assertThat(e.isTransitoria()).isTrue();
    }

    @Test
    @DisplayName("Célula sem conteúdo nem formato vai vazia (limpa o que havia); texto começado por = não vira fórmula")
    void celulas() {
        assertThat(PlanilhasGoogle.celula(Celula.VAZIA)).isEmpty();
        assertThat(PlanilhasGoogle.celula(Celula.texto("=SOMA(A1)")).get("userEnteredValue"))
                .isEqualTo(Map.of("stringValue", "=SOMA(A1)"));
        Map<String, Object> inteiro = PlanilhasGoogle.celula(Celula.formulaInteiro("SUM(M6:M36)", 84, Estilo.CENTRO));
        assertThat(inteiro.get("userEnteredValue")).isEqualTo(Map.of("formulaValue", "=SUM(M6:M36)"));
        assertThat(inteiro.get("userEnteredFormat").toString()).contains("CENTER").contains("NUMBER");
        assertThat(PlanilhasGoogle.celula(Celula.texto("obs", Estilo.NORMAL.comQuebra())).get("userEnteredFormat").toString())
                .contains("WRAP");
        assertThat(Files.exists(pasta)).isTrue();
    }
}
