package br.com.conferenciaponto.modulos.atendimento.application.retencao;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.PdfDigisacDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.atendimento.GerenciarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.PublicadorDeProgresso;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.pdf.ExtratorPdfBox;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence.RepositoriosDeTeste;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Retenção: os arquivos saem depois de 30 dias, o atendimento inteiro depois de 180. */
class LimpezaDosAtendimentosTest {

    @TempDir
    Path raiz;

    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();
    private final Arquivos arquivos = RepositoriosDeTeste.arquivos();

    private UUID criar(Usuario dono, Instant quando) {
        byte[] pdf = PdfDigisacDeTeste.exemplo().bytes();
        return new GerenciarAtendimentos(new ExtratorPdfBox(), atendimentos, arquivos,
                new PublicadorDeProgresso(atendimentos, arquivos, e -> { }), new PastaEmDisco(raiz),
                RepositoriosDeTeste.transacao(), Clock.fixed(quando, ZoneOffset.UTC), DataSize.ofMegabytes(50), 30, 180)
                .receberPdf(dono, new ByteArrayInputStream(pdf), pdf.length, "conversa.pdf").atendimento().id();
    }

    private LimpezaDosAtendimentos limpeza(Instant quando) {
        return new LimpezaDosAtendimentos(atendimentos, arquivos, RepositoriosDeTeste.tarefas(), new PastaEmDisco(raiz),
                Clock.fixed(quando, ZoneOffset.UTC));
    }

    @Test
    void osArquivosSaemDepoisDoPrazoEOAtendimentoInteiroDepoisDosTextos() {
        Usuario maria = new Usuario(PostgresDeTeste.novoUsuario(true), "maria", "maria", "h", true, Set.of(Perfil.ROLE_USER),
                null, null, false);
        Instant criado = Instant.now().plus(Duration.ofDays(4000)); // depois do que os outros testes criaram
        UUID id = criar(maria, criado);
        Usuario joao = new Usuario(PostgresDeTeste.novoUsuario(true), "joao", "joao", "h", true, Set.of(Perfil.ROLE_USER),
                null, null, false);
        UUID recente = criar(joao, criado.plus(Duration.ofDays(20)));
        Path pdf = raiz.resolve(id.toString()).resolve("conversa.pdf");
        assertThat(pdf).exists();

        assertThat(limpeza(criado.plus(Duration.ofDays(29))).limpar().arquivosApagados()).isZero();
        LimpezaDosAtendimentos.Resultado depoisDe30 = limpeza(criado.plus(Duration.ofDays(30))).limpar();

        assertThat(depoisDe30.arquivosApagados()).isGreaterThanOrEqualTo(1);
        assertThat(pdf).doesNotExist();
        assertThat(raiz.resolve(recente.toString()).resolve("conversa.pdf")).exists();
        assertThat(arquivos.doAtendimento(id)).allMatch(a -> a.situacao().equals("removido") && a.caminho() == null);
        assertThat(atendimentos.buscar(id, maria.id()).orElseThrow().arquivosApagadosEm()).isNotNull();
        assertThat(limpeza(criado.plus(Duration.ofDays(31))).limpar().arquivosApagados()).as("não repete").isZero();

        limpeza(criado.plus(Duration.ofDays(180))).limpar();

        assertThat(atendimentos.existe(id, maria.id())).isFalse();
        assertThat(raiz.resolve(id.toString())).doesNotExist();
        assertThat(atendimentos.existe(recente, joao.id())).isTrue();
    }
}
