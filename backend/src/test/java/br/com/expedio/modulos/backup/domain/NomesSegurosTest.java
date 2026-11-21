package br.com.expedio.modulos.backup.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NomesSegurosTest {

    private final Path destino = Path.of("C:\\Backups\\extraido");

    @Test
    void pastaDoClienteTrocaOQueOWindowsNaoAceita() {
        assertThat(NomesSeguros.pastaDoCliente("Loja: Centro/Filial?")).isEqualTo("Loja_ Centro_Filial_");
        assertThat(NomesSeguros.pastaDoCliente("Mercado São João.")).isEqualTo("Mercado São João");
        assertThat(NomesSeguros.pastaDoCliente("CON")).isEqualTo("_CON");
        assertThat(NomesSeguros.pastaDoCliente("   ")).isEqualTo("cliente");
    }

    @Test
    void caminhoNormalFicaDentroDoDestino() {
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/Data/CLIENTES.DAT"))
                .contains(destino.resolve("sislojas3").resolve("Data").resolve("CLIENTES.DAT"));
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/DDSrc/Relatório de Ações.dd")).isPresent();
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/Vazia/")).isPresent();
    }

    @Test
    void caminhoPerigosoEhRecusado() {
        assertThat(NomesSeguros.dentroDe(destino, "../fora.txt")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/../../fora.txt")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "/etc/passwd")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "C:/Windows/win.ini")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3\\..\\fora.txt")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/NUL.txt")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "sislojas3/nome.")).isEmpty();
        assertThat(NomesSeguros.dentroDe(destino, "")).isEmpty();
    }
}
