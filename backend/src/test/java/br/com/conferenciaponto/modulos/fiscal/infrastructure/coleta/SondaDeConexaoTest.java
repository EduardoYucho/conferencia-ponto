package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
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
    @DisplayName("Handshake completo com um servidor que não manda o close_notify: o tempo é o do handshake, sem esperar o fechamento")
    void handshakeCompletoSemEsperarOFechamento() throws Exception {
        SSLContext contexto = contextoDeTeste();
        int porta = servidores.tlsQueNaoFecha(contexto);
        SondaDeConexao comConfianca = new SondaDeConexao(Duration.ofSeconds(5), Duration.ofSeconds(3),
                contexto.getSocketFactory(), InetAddress::getAllByName);
        long inicio = System.nanoTime();

        SondaDeConexao.Resposta r = comConfianca.testar("127.0.0.1:" + porta);

        assertThat(r.situacao()).isEqualTo(Situacao.NORMAL);
        assertThat(r.tempoMs()).isLessThan(2000);
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("Um nome com dois servidores: um recusa e o outro responde = instável; os dois fora = fora, com a conta")
    void doisServidores() throws IOException {
        InetAddress segundo = InetAddress.getByName("127.0.0.2");
        int porta = servidores.queRespondeEm(segundo);
        InetAddress primeiro = InetAddress.getByName("127.0.0.1");
        SondaDeConexao comDois = new SondaDeConexao(Duration.ofSeconds(2), Duration.ofSeconds(1),
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(),
                host -> new InetAddress[] {primeiro, segundo});

        SondaDeConexao.Resposta metade = comDois.testar("dois.exemplo:" + porta);
        assertThat(metade.situacao()).isEqualTo(Situacao.INSTAVEL);
        assertThat(metade.detalhe()).startsWith("1 de 2 servidores deste endereço não responderam (conexão recusada pelo servidor)");
        assertThat(metade.tempoMs()).isNotNull();

        int fechada = ServidoresDeTeste.fechada();
        SondaDeConexao.Resposta nenhum = comDois.testar("dois.exemplo:" + fechada);
        assertThat(nenhum.situacao()).isEqualTo(Situacao.FORA);
        assertThat(nenhum.detalhe()).isEqualTo("Conexão recusada pelo servidor (2 de 2 servidores testados).");
    }

    /** O certificado de teste (sintético, só para estes testes) como chave do servidor e como confiança do cliente. */
    private static SSLContext contextoDeTeste() throws Exception {
        KeyStore chaves = KeyStore.getInstance("PKCS12");
        try (InputStream in = SondaDeConexaoTest.class.getResourceAsStream("/fiscal/servidor-tls-de-teste.p12")) {
            chaves.load(in, SENHA_DE_TESTE);
        }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(chaves, SENHA_DE_TESTE);
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(chaves);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return contexto;
    }

    private static final char[] SENHA_DE_TESTE = "teste-fiscal".toCharArray();

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
