package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** O teste de conexão contra servidores locais (nenhum acesso à internet). */
class SondaDeConexaoTest {

    private final ServidoresDeTeste servidores = new ServidoresDeTeste();
    private final SondaDeConexao sonda = new SondaDeConexao(Duration.ofSeconds(2), Duration.ofSeconds(1));

    @AfterEach
    void fechar() throws IOException {
        servidores.close();
    }

    @Test
    @DisplayName("Um servidor que responde (mesmo recusando a conexão segura) está no ar, com o tempo da resposta")
    void respondeuEstaNoAr() throws IOException {
        int porta = servidores.queResponde(0);

        SondaDeConexao.Resposta r = sonda.testar("127.0.0.1:" + porta);

        assertThat(r.situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(r.respondeu()).isTrue();
        assertThat(r.tempoMs()).isNotNull().isLessThan(1000);
        assertThat(r.detalhe()).startsWith("Respondeu em ").endsWith("(teste de conexão, sem certificado).");
    }

    @Test
    @DisplayName("Respondeu acima do limite de lentidão: instável")
    void lento() throws IOException {
        int porta = servidores.queResponde(1300);

        SondaDeConexao.Resposta r = sonda.testar("127.0.0.1:" + porta);

        assertThat(r.situacao()).isEqualTo(Situacao.INSTAVEL);
        assertThat(r.tempoMs()).isGreaterThanOrEqualTo(1000);
        assertThat(r.detalhe()).startsWith("Respondeu, mas devagar");
    }

    @Test
    @DisplayName("Aceita a conexão e não responde à conexão segura: fora, depois do tempo limite")
    void calado() throws IOException {
        int porta = servidores.calado();
        long inicio = System.nanoTime();

        SondaDeConexao.Resposta r = sonda.testar("127.0.0.1:" + porta);

        assertThat(r.situacao()).isEqualTo(Situacao.FORA);
        assertThat(r.detalhe()).isEqualTo("Conectou, mas não respondeu à conexão segura em 2 s.");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    @DisplayName("Porta fechada: fora (conexão recusada); endereço que não existe: fora (DNS); endereço inválido: sem informação")
    void foraDoAr() throws IOException {
        SondaDeConexao.Resposta recusada = sonda.testar("127.0.0.1:" + ServidoresDeTeste.fechada());
        assertThat(recusada.situacao()).isEqualTo(Situacao.FORA);
        assertThat(recusada.detalhe()).isEqualTo("Conexão recusada pelo servidor.");

        SondaDeConexao.Resposta dns = sonda.testar("servidor-que-nao-existe.invalid");
        assertThat(dns.situacao()).isEqualTo(Situacao.FORA);
        assertThat(dns.respondeu()).isFalse();
        assertThat(dns.detalhe()).isEqualTo("Endereço não encontrado (DNS).");

        assertThat(sonda.testar("127.0.0.1:porta").situacao()).isEqualTo(Situacao.SEM_INFORMACAO);
    }
}
