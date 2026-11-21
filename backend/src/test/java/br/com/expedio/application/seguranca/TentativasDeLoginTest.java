package br.com.expedio.application.seguranca;

import br.com.expedio.domain.exception.MuitasTentativasException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TentativasDeLoginTest {

    private final Relogio relogio = new Relogio(Instant.parse("2026-11-17T14:00:00Z"));
    private final TentativasDeLogin tentativas = new TentativasDeLogin(relogio);

    private void errar(String login, String origem, int vezes) {
        for (int i = 0; i < vezes; i++) {
            tentativas.conferir(login, origem);
            tentativas.falhou(login, origem);
        }
    }

    @Test
    void cincoSenhasErradasBloqueiamOLoginPor15Minutos() {
        errar("maria", "10.0.0.5", 4);
        assertThatCode(() -> tentativas.conferir("maria", "10.0.0.5")).doesNotThrowAnyException();
        errar("maria", "10.0.0.5", 1);

        assertThatThrownBy(() -> tentativas.conferir("Maria ", "10.0.0.9"))
                .isInstanceOf(MuitasTentativasException.class)
                .hasMessageContaining("Muitas senhas erradas para este login. Espere 15 minutos")
                .satisfies(e -> assertThat(((MuitasTentativasException) e).getCodigo()).isEqualTo("LOGIN_BLOQUEADO"))
                .satisfies(e -> assertThat(((MuitasTentativasException) e).getEspera()).isEqualTo(Duration.ofMinutes(15)));
        assertThatCode(() -> tentativas.conferir("joao", "10.0.0.5")).as("os outros logins seguem")
                .doesNotThrowAnyException();

        relogio.agora = relogio.agora.plus(Duration.ofMinutes(14).plusSeconds(1));
        assertThatThrownBy(() -> tentativas.conferir("maria", null)).hasMessageContaining("Espere 1 minuto ");
        relogio.agora = relogio.agora.plus(Duration.ofMinutes(1));
        assertThatCode(() -> tentativas.conferir("maria", null)).doesNotThrowAnyException();
    }

    @Test
    void errosEspalhadosAlemDaJanelaNaoSomam() {
        errar("maria", null, 4);
        relogio.agora = relogio.agora.plus(Duration.ofMinutes(16));
        errar("maria", null, 4);
        assertThatCode(() -> tentativas.conferir("maria", null)).doesNotThrowAnyException();
    }

    @Test
    void entrarComASenhaCertaZeraOLogin() {
        errar("maria", null, 4);
        tentativas.entrou("maria");
        errar("maria", null, 4);
        assertThatCode(() -> tentativas.conferir("maria", null)).doesNotThrowAnyException();
    }

    @Test
    void vinteErrosDoMesmoComputadorBloqueiamOComputador() {
        for (int i = 0; i < 20; i++) {
            errar("login" + i, "10.0.0.7", 1); // um login diferente a cada vez: nenhum chega a 5
        }
        assertThatThrownBy(() -> tentativas.conferir("eduardo", "10.0.0.7"))
                .isInstanceOf(MuitasTentativasException.class)
                .hasMessageContaining("a partir deste computador");
        assertThatCode(() -> tentativas.conferir("eduardo", "10.0.0.8")).doesNotThrowAnyException();
    }

    @Test
    void muitosLoginsInventadosNaoOcupamAMemoriaSemLimite() {
        for (int i = 0; i < TentativasDeLogin.MAXIMO_DE_CHAVES + 50; i++) {
            tentativas.falhou("inventado" + i, null);
        }
        errar("maria", null, 5);
        assertThatThrownBy(() -> tentativas.conferir("maria", null)).isInstanceOf(MuitasTentativasException.class);
    }

    private static final class Relogio extends Clock {
        Instant agora;

        Relogio(Instant agora) {
            this.agora = agora;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
