package br.com.conferenciaponto.infrastructure.log;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contexto do log (usuário e protocolo), arquivo por usuário e por hora, e a leitura desses arquivos. */
class LogsPorUsuarioTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    @TempDir
    Path pasta;

    private ArquivosDeLog arquivos(int dias) {
        return new ArquivosDeLog(new LogsProperties(pasta.toString(), dias),
                Clock.fixed(Instant.parse("2026-10-02T18:00:00Z"), FUSO));
    }

    private void gravar(String login, String dia, String arquivo, String texto) throws IOException {
        Path destino = pasta.resolve("usuarios").resolve(login).resolve(dia);
        Files.createDirectories(destino);
        Files.writeString(destino.resolve(arquivo), texto, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Cada linha cai em <login>/<aaaa-mm-dd>/<hh>h, na hora do fuso do sistema; sem usuário vai para 'sistema'")
    void caminhoDoArquivo() {
        Instant quando = Instant.parse("2026-10-02T17:59:59Z"); // 14:59:59 em São Paulo
        assertThat(DiscriminadorUsuarioHora.caminho("maria", quando, FUSO)).isEqualTo("maria/2026-10-02/14h");
        assertThat(DiscriminadorUsuarioHora.caminho("maria", quando.plusSeconds(1), FUSO)).isEqualTo("maria/2026-10-02/15h");
        assertThat(DiscriminadorUsuarioHora.caminho(null, quando, FUSO)).isEqualTo("sistema/2026-10-02/14h");
        assertThat(DiscriminadorUsuarioHora.caminho("  ", quando, FUSO)).startsWith("sistema/");
        // virada do dia pelo fuso do sistema, não pelo UTC
        assertThat(DiscriminadorUsuarioHora.caminho("maria", Instant.parse("2026-10-03T02:30:00Z"), FUSO))
                .isEqualTo("maria/2026-10-02/23h");
    }

    @Test
    @DisplayName("O login nunca vira um caminho fora da pasta de logs")
    void pastaSegura() {
        assertThat(ContextoDeLog.pastaDo("Maria.Silva")).isEqualTo("maria.silva");
        assertThat(ContextoDeLog.pastaDo("../../etc/passwd")).doesNotContain("/").doesNotStartWith(".");
        assertThat(ContextoDeLog.pastaDo("..")).doesNotStartWith(".");
        assertThat(ContextoDeLog.pastaDo("a\\b:c")).isEqualTo("a_b_c");
        assertThat(ContextoDeLog.pastaDo(null)).isEqualTo("sistema");
        assertThat(ContextoDeLog.pastaDo("x".repeat(200))).hasSize(60);
    }

    @Test
    @DisplayName("Protocolo: 6 caracteres fáceis de ditar (sem 0/O, 1/I/L) e diferentes a cada requisição")
    void protocolo() {
        List<String> gerados = java.util.stream.Stream.generate(ContextoDeLog::novoProtocolo).limit(500).toList();
        assertThat(gerados).allMatch(p -> p.matches("[2-9A-HJKMNP-Z]{6}"));
        assertThat(gerados.stream().distinct().count()).isGreaterThan(495);
    }

    @Test
    @DisplayName("Tarefa em nome de uma pessoa: o usuário entra no contexto e o anterior volta no fim, mesmo com erro")
    void comUsuario() {
        MDC.clear();
        ContextoDeLog.definirUsuario("admin");
        ContextoDeLog.comUsuario("maria", () -> assertThat(ContextoDeLog.usuario()).isEqualTo("maria"));
        assertThat(ContextoDeLog.usuario()).isEqualTo("admin");

        assertThatThrownBy(() -> ContextoDeLog.comUsuario("joana", () -> {
            throw new IllegalStateException("falhou");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ContextoDeLog.usuario()).isEqualTo("admin");

        ContextoDeLog.definirUsuario(null);
        assertThat(ContextoDeLog.usuario()).isEqualTo("sistema");
        MDC.clear();
    }

    @Test
    @DisplayName("O contexto do log acompanha a tarefa para a thread de segundo plano e não vaza para a próxima")
    void contextoNasTarefas() throws Exception {
        MDC.clear();
        MDC.put(ContextoDeLog.USUARIO, "maria");
        MDC.put(ContextoDeLog.PROTOCOLO, "ABC234");
        String[] visto = new String[3];
        Runnable tarefa = new ContextoNasTarefas().decorate(() -> {
            visto[0] = MDC.get(ContextoDeLog.USUARIO);
            visto[1] = MDC.get(ContextoDeLog.PROTOCOLO);
        });
        MDC.clear();

        Thread outra = new Thread(() -> {
            tarefa.run();
            visto[2] = MDC.get(ContextoDeLog.USUARIO); // depois da tarefa, a thread fica limpa
        });
        outra.start();
        outra.join();

        assertThat(visto[0]).isEqualTo("maria");
        assertThat(visto[1]).isEqualTo("ABC234");
        assertThat(visto[2]).isNull();
    }

    @Test
    @DisplayName("Lista: 'sistema' primeiro, dias e horas do mais recente para o mais antigo; ignora o que não é log")
    void listar() throws IOException {
        gravar("maria", "2026-10-01", "09h.log", "a");
        gravar("maria", "2026-10-02", "08h.log", "bb");
        gravar("maria", "2026-10-02", "14h.log", "ccc");
        gravar("maria", "2026-10-02", "anotacoes.txt", "não é log");
        gravar("sistema", "2026-10-02", "00h.log", "x");
        gravar("admin", "nao-e-data", "10h.log", "x");

        List<ArquivosDeLog.Pasta> pastas = arquivos(30).listar();

        assertThat(pastas).extracting(ArquivosDeLog.Pasta::login).containsExactly("sistema", "maria");
        ArquivosDeLog.Pasta maria = pastas.get(1);
        assertThat(maria.dias()).extracting(ArquivosDeLog.Dia::data)
                .containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1));
        assertThat(maria.dias().get(0).horas()).extracting(ArquivosDeLog.Hora::hora).containsExactly(14, 8);
        assertThat(maria.dias().get(0).horas().get(0).bytes()).isEqualTo(3);
        assertThat(new ArquivosDeLog(new LogsProperties(pasta.resolve("nao-existe").toString(), 30),
                Clock.systemUTC()).listar()).isEmpty();
    }

    @Test
    @DisplayName("Leitura: devolve o arquivo da hora; arquivo grande vem só o fim, começando numa linha inteira")
    void ler() throws IOException {
        gravar("maria", "2026-10-02", "14h.log", "14:00:01 INFO  [ABC234] acesso - GET /api/v1/jornadas -> 200\n");
        ArquivosDeLog.Conteudo pequeno = arquivos(30).ler("maria", LocalDate.of(2026, 10, 2), 14);
        assertThat(pequeno.texto()).contains("[ABC234]");
        assertThat(pequeno.cortado()).isFalse();

        String linha = "linha de log com cinquenta caracteres exatamente!\n";
        gravar("maria", "2026-10-02", "15h.log", "INICIO DO ARQUIVO\n" + linha.repeat(ArquivosDeLog.BYTES_NA_TELA / 40));
        ArquivosDeLog.Conteudo grande = arquivos(30).ler("maria", LocalDate.of(2026, 10, 2), 15);
        assertThat(grande.cortado()).isTrue();
        assertThat(grande.texto()).doesNotContain("INICIO DO ARQUIVO").startsWith("linha de log").endsWith(linha);
        assertThat(grande.texto().length()).isLessThanOrEqualTo(ArquivosDeLog.BYTES_NA_TELA);
    }

    @Test
    @DisplayName("Leitura recusa login que tenta sair da pasta, hora inválida e arquivo que não existe (com frase clara)")
    void lerRecusas() throws IOException {
        gravar("maria", "2026-10-02", "14h.log", "x");
        Files.writeString(pasta.resolve("segredo.log"), "fora da pasta dos usuários");
        ArquivosDeLog arquivos = arquivos(30);
        LocalDate dia = LocalDate.of(2026, 10, 2);

        for (String login : new String[]{"..", "../maria", "maria/../..", "", null, "MARIA", "a b"}) {
            assertThatThrownBy(() -> arquivos.ler(login, dia, 14)).as("login %s", login)
                    .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Escolha o usuário");
        }
        assertThatThrownBy(() -> arquivos.ler("maria", dia, 24)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> arquivos.ler("maria", null, 14)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> arquivos.ler("maria", dia, 9)).isInstanceOf(RecursoNaoEncontradoException.class)
                .hasMessage("Não há log de maria nesse dia e hora.");
    }

    @Test
    @DisplayName("Limpeza: apaga só os dias mais antigos que o prazo de guarda; prazo zero não apaga nada")
    void limparAntigos() throws IOException {
        gravar("maria", "2026-09-01", "09h.log", "antigo");
        gravar("maria", "2026-09-02", "09h.log", "no limite");
        gravar("maria", "2026-10-02", "09h.log", "de hoje");
        gravar("sistema", "2026-08-15", "00h.log", "antigo");

        assertThat(arquivos(0).limpar(LocalDate.of(2026, 10, 2))).isZero();
        int apagados = arquivos(30).limpar(LocalDate.of(2026, 10, 2)); // guarda de 02/09 em diante

        assertThat(apagados).isEqualTo(2);
        Path usuarios = pasta.resolve("usuarios");
        assertThat(usuarios.resolve("maria/2026-09-01")).doesNotExist();
        assertThat(usuarios.resolve("sistema/2026-08-15")).doesNotExist();
        assertThat(usuarios.resolve("maria/2026-09-02/09h.log")).exists();
        assertThat(usuarios.resolve("maria/2026-10-02/09h.log")).exists();
    }
}
