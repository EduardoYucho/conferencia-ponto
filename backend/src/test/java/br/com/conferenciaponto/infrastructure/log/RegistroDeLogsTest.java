package br.com.conferenciaponto.infrastructure.log;

import br.com.conferenciaponto.infrastructure.log.RegistroDeLogs.Linha;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Memória das últimas linhas de log (o que a tela "Logs → Ao vivo" consulta). */
class RegistroDeLogsTest {

    private static final Instant AGORA = Instant.parse("2026-10-02T17:00:00Z");

    @BeforeEach
    void limpar() {
        RegistroDeLogs.limpar();
    }

    private static void linha(String nivel, String usuario, String protocolo, String origem, String mensagem) {
        RegistroDeLogs.registrar(AGORA, nivel, usuario, protocolo, origem, mensagem, null);
    }

    private static List<String> mensagens(List<Linha> linhas) {
        return linhas.stream().map(Linha::mensagem).toList();
    }

    @Test
    @DisplayName("Filtros: por pessoa, por gravidade mínima e por texto (mensagem, protocolo, origem ou pilha do erro)")
    void filtros() {
        long inicio = RegistroDeLogs.ultimaSequencia();
        linha("INFO", "maria", "AAA222", "acesso", "GET /api/v1/jornadas -> 200 (5 ms)");
        linha("WARN", "maria", "BBB333", "acesso", "POST /api/v1/jornadas/batidas -> 422 (7 ms)");
        linha("ERROR", "joana", "CCC444", "GlobalExceptionHandler", "Erro inesperado (protocolo CCC444)");
        linha("INFO", null, null, "PrazoCicloBancoAgendado", "Banco de horas conferido");
        RegistroDeLogs.registrar(AGORA, "ERROR", "admin", "DDD555", "ConciliacaoEmSegundoPlano", "Falha ao conferir",
                "java.lang.IllegalStateException: relatório sumiu\n\tat x.y.Z.metodo(Z.java:10)");

        assertThat(RegistroDeLogs.depoisDe(inicio, null, null, null, 100)).hasSize(5);
        assertThat(mensagens(RegistroDeLogs.depoisDe(inicio, null, "MARIA", null, 100))).hasSize(2)
                .allMatch(m -> m.contains("/jornadas"));
        assertThat(RegistroDeLogs.depoisDe(inicio, null, "sistema", null, 100)).extracting(Linha::origem)
                .containsExactly("PrazoCicloBancoAgendado"); // sem usuário = "sistema"
        assertThat(RegistroDeLogs.depoisDe(inicio, "WARN", null, null, 100)).extracting(Linha::nivel)
                .containsExactly("WARN", "ERROR", "ERROR");
        assertThat(RegistroDeLogs.depoisDe(inicio, "ERROR", null, null, 100)).extracting(Linha::usuario)
                .containsExactly("joana", "admin");
        assertThat(RegistroDeLogs.depoisDe(inicio, null, null, "ccc444", 100)).extracting(Linha::usuario).containsExactly("joana");
        assertThat(RegistroDeLogs.depoisDe(inicio, null, null, "relatório sumiu", 100)).extracting(Linha::protocolo)
                .containsExactly("DDD555");
        assertThat(RegistroDeLogs.depoisDe(inicio, null, null, "nada disso", 100)).isEmpty();
    }

    @Test
    @DisplayName("'Sem as consultas' tira só as leituras que deram certo: o que grava, o que foi recusado e o resto ficam")
    void semConsultas() {
        long inicio = RegistroDeLogs.ultimaSequencia();
        linha("INFO", "maria", "A", "acesso", "GET /api/v1/jornadas -> 200 (5 ms)");
        linha("WARN", "maria", "B", "acesso", "GET /api/v1/logs/ao-vivo -> 403 (1 ms)");
        linha("INFO", "maria", "C", "acesso", "POST /api/v1/jornadas/batidas -> 201 (9 ms)");
        linha("INFO", "maria", "D", "AuthController", "Entrou no sistema");

        assertThat(RegistroDeLogs.depoisDe(inicio, null, null, null, 100, true)).extracting(Linha::protocolo)
                .containsExactly("B", "C", "D");
    }

    @Test
    @DisplayName("Consulta incremental: só o que veio depois da sequência; com limite, ficam as mais recentes")
    void incrementalELimite() {
        long inicio = RegistroDeLogs.ultimaSequencia();
        for (int i = 1; i <= 10; i++) {
            linha("INFO", "maria", null, "x", "linha " + i);
        }
        List<Linha> ultimas = RegistroDeLogs.depoisDe(inicio, null, null, null, 3);
        assertThat(mensagens(ultimas)).containsExactly("linha 8", "linha 9", "linha 10");

        long visto = ultimas.get(2).seq();
        assertThat(RegistroDeLogs.depoisDe(visto, null, null, null, 100)).isEmpty();
        linha("INFO", "maria", null, "x", "linha 11");
        assertThat(mensagens(RegistroDeLogs.depoisDe(visto, null, null, null, 100))).containsExactly("linha 11");
        assertThat(RegistroDeLogs.ultimaSequencia()).isEqualTo(visto + 1);
    }

    @Test
    @DisplayName("A memória tem tamanho fixo: as linhas mais antigas saem; mensagem enorme é cortada")
    void capacidade() {
        long inicio = RegistroDeLogs.ultimaSequencia();
        for (int i = 1; i <= RegistroDeLogs.CAPACIDADE + 50; i++) {
            linha("INFO", "maria", null, "x", "linha " + i);
        }
        List<Linha> todas = RegistroDeLogs.depoisDe(inicio, null, null, null, Integer.MAX_VALUE);
        assertThat(todas).hasSize(RegistroDeLogs.CAPACIDADE);
        assertThat(todas.get(0).mensagem()).isEqualTo("linha 51");

        linha("INFO", "maria", null, "x", "m".repeat(50_000));
        Linha enorme = RegistroDeLogs.depoisDe(RegistroDeLogs.ultimaSequencia() - 1, null, null, null, 1).get(0);
        assertThat(enorme.mensagem().length()).isLessThan(2_100);
    }
}
