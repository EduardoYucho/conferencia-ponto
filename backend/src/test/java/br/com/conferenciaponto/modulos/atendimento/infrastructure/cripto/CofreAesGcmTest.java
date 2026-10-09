package br.com.conferenciaponto.modulos.atendimento.infrastructure.cripto;

import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveCifrada;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.CofreException;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.ChaveMestraProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CofreAesGcmTest {

    private static final String CHAVE = "AIzaSyD-chave-de-teste_0123456789abcd";
    private final UUID maria = UUID.randomUUID();
    private final UUID joao = UUID.randomUUID();

    @TempDir
    Path pasta;

    private CofreAesGcm cofre(Path arquivo) {
        return new CofreAesGcm(new ChaveMestraProperties(arquivo));
    }

    @Test
    void cifraEDecifraParaOMesmoDono() {
        CofreAesGcm cofre = cofre(pasta.resolve("chave-mestra"));

        ChaveCifrada cifrada = cofre.cifrar(CHAVE, maria);

        assertThat(new String(cifrada.cifrada(), StandardCharsets.ISO_8859_1)).doesNotContain(CHAVE);
        assertThat(cifrada.vetorInicial()).hasSize(12);
        assertThat(cifrada.versaoChaveMestra()).isEqualTo(1);
        assertThat(cofre.decifrar(cifrada, maria)).isEqualTo(CHAVE);
    }

    @Test
    void cadaCifraUsaUmVetorNovo() {
        CofreAesGcm cofre = cofre(pasta.resolve("chave-mestra"));

        ChaveCifrada primeira = cofre.cifrar(CHAVE, maria);
        ChaveCifrada segunda = cofre.cifrar(CHAVE, maria);

        assertThat(primeira.vetorInicial()).isNotEqualTo(segunda.vetorInicial());
        assertThat(primeira.cifrada()).isNotEqualTo(segunda.cifrada());
    }

    @Test
    void aChaveDeUmaPessoaNaoAbreParaOutra() {
        CofreAesGcm cofre = cofre(pasta.resolve("chave-mestra"));
        ChaveCifrada daMaria = cofre.cifrar(CHAVE, maria);

        assertThatThrownBy(() -> cofre.decifrar(daMaria, joao))
                .isInstanceOf(CofreException.class)
                .extracting("codigo").isEqualTo("CHAVE_MESTRA_TROCADA");
    }

    @Test
    void criaAChaveMestraNaPrimeiraVezEOutroCofreComOMesmoArquivoAbre() throws Exception {
        Path arquivo = pasta.resolve("sub/pasta/chave-mestra");
        ChaveCifrada cifrada = cofre(arquivo).cifrar(CHAVE, maria);

        String conteudo = Files.readString(arquivo).strip();
        assertThat(conteudo).startsWith("v1:");
        assertThat(Base64.getDecoder().decode(conteudo.substring(3))).hasSize(32);
        assertThat(cofre(arquivo).decifrar(cifrada, maria)).isEqualTo(CHAVE);
    }

    @Test
    void chaveMestraApagadaFazAsChavesGuardadasPararDeAbrir() throws Exception {
        Path arquivo = pasta.resolve("chave-mestra");
        ChaveCifrada cifrada = cofre(arquivo).cifrar(CHAVE, maria);
        Files.delete(arquivo);

        assertThatThrownBy(() -> cofre(arquivo).decifrar(cifrada, maria))
                .isInstanceOf(CofreException.class)
                .hasMessageContaining("Cadastre a sua chave de novo")
                .extracting("codigo").isEqualTo("CHAVE_MESTRA_TROCADA");
        assertThat(arquivo).exists();
    }

    @Test
    void arquivoEstragadoNaoESobrescrito() throws Exception {
        Path arquivo = pasta.resolve("chave-mestra");
        Files.writeString(arquivo, "isto não é uma chave");

        assertThatThrownBy(() -> cofre(arquivo).cifrar(CHAVE, maria))
                .isInstanceOf(CofreException.class)
                .hasMessageContaining("Restaure-o do backup")
                .extracting("codigo").isEqualTo("CHAVE_MESTRA_ILEGIVEL");
        assertThat(Files.readString(arquivo)).isEqualTo("isto não é uma chave");
    }

    @Test
    void cifraAlteradaNoBancoEDetectada() {
        CofreAesGcm cofre = cofre(pasta.resolve("chave-mestra"));
        ChaveCifrada original = cofre.cifrar(CHAVE, maria);
        byte[] bytes = original.cifrada();
        bytes[0] ^= 1;
        ChaveCifrada alterada = new ChaveCifrada(bytes, original.vetorInicial(), original.versaoChaveMestra());

        assertThatThrownBy(() -> cofre.decifrar(alterada, maria)).isInstanceOf(CofreException.class);
    }

    @Test
    void versaoDeChaveMestraDesconhecidaNaoAbre() {
        CofreAesGcm cofre = cofre(pasta.resolve("chave-mestra"));
        ChaveCifrada original = cofre.cifrar(CHAVE, maria);
        ChaveCifrada deOutraVersao = new ChaveCifrada(original.cifrada(), original.vetorInicial(), 2);

        assertThatThrownBy(() -> cofre.decifrar(deOutraVersao, maria))
                .isInstanceOf(CofreException.class)
                .extracting("codigo").isEqualTo("CHAVE_MESTRA_TROCADA");
    }

    @Test
    void oTextoDaChaveCifradaNaoMostraOConteudo() {
        ChaveCifrada cifrada = cofre(pasta.resolve("chave-mestra")).cifrar(CHAVE, maria);

        assertThat(cifrada.toString()).doesNotContain(CHAVE).contains("bytes");
    }
}
