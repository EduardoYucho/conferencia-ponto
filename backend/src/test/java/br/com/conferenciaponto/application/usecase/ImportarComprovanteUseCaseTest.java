package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.usecase.ImportarComprovanteUseCase.Comprovante;
import br.com.conferenciaponto.application.usecase.ImportarComprovanteUseCase.Resultado;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.HashSha256;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ImportarComprovanteUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final ComprovantePontoRepositoryEmMemoria comprovantes = new ComprovantePontoRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final ArmazenamentoEmMemoria armazenamento = new ArmazenamentoEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(data -> false);
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(DIA, LocalTime.of(18, 0), SP).toInstant(), SP);

    private final ImportarComprovanteUseCase useCase = new ImportarComprovanteUseCase(
            registros, comprovantes, arquivos, armazenamento, classificador, motor, eventos::add, clock);

    private int sequencia;

    /** Cada chamada simula um arquivo diferente (conteúdo e hash únicos). */
    private Optional<Resultado> importar(String horario) {
        return enviar(horario, ("%PDF-1.4 comprovante " + horario + " #" + (++sequencia)).getBytes());
    }

    /** Mesma identidade = mesmo conteúdo = mesmo hash (o "mesmo arquivo" baixado de novo). */
    private Optional<Resultado> importar(String horario, String identidade) {
        return enviar(horario, ("%PDF-1.4 " + identidade).getBytes());
    }

    private Optional<Resultado> enviar(String horario, byte[] pdf) {
        Optional<LocalDateTime> dataHora = horario == null
                ? Optional.empty()
                : Optional.of(LocalDateTime.of(DIA, LocalTime.parse(horario)));
        return useCase.executar(new Comprovante("Comprovante " + sequencia + ".pdf", HashSha256.de(pdf), dataHora, pdf));
    }

    private Batidas batidasDoDia() {
        return registros.buscarPorData(DIA).orElseThrow().getBatidas();
    }

    @Test
    @DisplayName("Carga de 28/09: cada PDF vai para a 1ª coluna livre, e a jornada fecha com saldo zero")
    void rotaBatidasNaOrdem() {
        assertThat(importar("08:02:31")).map(Resultado::status).contains(StatusImportacao.IMPORTADO);
        importar("12:00:15");
        Resultado terceira = importar("12:59:59").orElseThrow();

        assertThat(terceira.registro().status()).isEqualTo(StatusJornada.EM_ANDAMENTO);
        assertThat(terceira.mensagem()).startsWith("Entrada 2 registrada às 12:59:59");

        Resultado ultima = importar("17:50:10").orElseThrow();
        assertThat(ultima.registro().status()).isEqualTo(StatusJornada.FECHADA);
        assertThat(ultima.registro().saldoDiarioSegundos()).isZero();
        assertThat(batidasDoDia().saida2()).isEqualTo(LocalTime.of(17, 50, 10));

        assertThat(eventos).hasSize(4).allSatisfy(e -> {
            assertThat(e).isInstanceOf(JornadaAtualizadaEvento.class);
            assertThat(((JornadaAtualizadaEvento) e).origem()).isEqualTo(OrigemAtualizacao.COMPROVANTE_PDF);
        });
        assertThat(comprovantes.salvos).extracting("status").containsOnly(StatusImportacao.IMPORTADO);
    }

    @Test
    @DisplayName("PDFs fora de ordem são encaixados cronologicamente")
    void foraDeOrdem() {
        importar("12:59:59");
        importar("08:02:31");
        importar("17:50:10");
        importar("12:00:15");

        Batidas b = batidasDoDia();
        assertThat(List.of(b.entrada1(), b.saida1(), b.entrada2(), b.saida2())).containsExactly(
                LocalTime.of(8, 2, 31), LocalTime.of(12, 0, 15), LocalTime.of(12, 59, 59), LocalTime.of(17, 50, 10));
        assertThat(b.posicaoDe(LocalTime.of(12, 0, 15))).contains(TipoBatida.SAIDA_1);
    }

    @Test
    @DisplayName("Mesmo arquivo (hash) é ignorado sem registrar nada novo")
    void mesmoArquivo() {
        importar("08:02:31", "abc");
        assertThat(importar("08:02:31", "abc")).isEmpty();
        assertThat(comprovantes.salvos).hasSize(1);
        assertThat(batidasDoDia().quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("Outro arquivo com a mesma data/hora é marcado como DUPLICADO")
    void mesmaBatidaOutroArquivo() {
        importar("08:02:31");
        Resultado r = importar("08:02:31").orElseThrow();

        assertThat(r.status()).isEqualTo(StatusImportacao.DUPLICADO);
        assertThat(batidasDoDia().quantidade()).isEqualTo(1);
        assertThat(eventos.get(1)).isInstanceOf(ComprovanteNaoImportadoEvento.class);
    }

    @Test
    @DisplayName("Batida a menos de 1 min de uma já registrada (ex.: botão 'bater ponto') é DUPLICADO")
    void janelaDeDuplicidade() {
        new RegistrarBatidaUseCase(registros, classificador, motor, e -> { }, clock)
                .executar(DIA, LocalTime.of(8, 2, 50));

        assertThat(importar("08:02:31")).map(Resultado::status).contains(StatusImportacao.DUPLICADO);
        assertThat(batidasDoDia().quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("PDF sem o padrão é INVALIDO; data futura e 7ª batida são REJEITADO")
    void naoImportados() {
        assertThat(importar(null)).map(Resultado::status).contains(StatusImportacao.INVALIDO);

        Resultado futuro = useCase.executar(new Comprovante("futuro.pdf", "f",
                Optional.of(LocalDateTime.of(DIA, LocalTime.of(18, 30))), new byte[]{1})).orElseThrow();
        assertThat(futuro.status()).isEqualTo(StatusImportacao.REJEITADO);

        importar("08:00:00");
        importar("12:00:00");
        importar("13:00:00");
        importar("17:48:00");
        assertThat(importar("17:50:00")).map(Resultado::status).contains(StatusImportacao.IMPORTADO); // 5ª
        assertThat(importar("17:52:00")).map(Resultado::status).contains(StatusImportacao.IMPORTADO); // 6ª
        Resultado setima = importar("17:55:00").orElseThrow();
        assertThat(setima.status()).isEqualTo(StatusImportacao.REJEITADO);
        assertThat(setima.mensagem()).contains("6 batidas");
    }

    // ------------------------------------------------------------ armazenamento

    private Map<TipoBatida, LocalDateTime> tiposArquivados() {
        return arquivos.salvos.values().stream()
                .collect(Collectors.toMap(ComprovanteArquivado::tipoBatida, ComprovanteArquivado::dataHoraBatida));
    }

    @Test
    @DisplayName("Cada PDF importado é arquivado (comprovante_<uuid>.pdf) e vinculado ao dia e à batida")
    void arquivaComprovante() {
        importar("08:02:31");

        assertThat(arquivos.salvos).hasSize(1);
        ComprovanteArquivado arquivo = arquivos.salvos.values().iterator().next();
        assertThat(arquivo.registroJornadaId()).isEqualTo(registros.buscarPorData(DIA).orElseThrow().getId());
        assertThat(arquivo.tipoBatida()).isEqualTo(TipoBatida.ENTRADA_1);
        assertThat(arquivo.caminhoArquivo()).isEqualTo("2026/09/comprovante_" + arquivo.id() + ".pdf");
        assertThat(arquivo.integro(armazenamento.arquivos.get(arquivo.caminhoArquivo()))).isTrue();
    }

    @Test
    @DisplayName("PDF fora de ordem: os vínculos de tipo de batida acompanham a reorganização")
    void reorganizaTiposArquivados() {
        importar("12:59:59");
        importar("12:00:15");
        importar("08:02:31");

        assertThat(tiposArquivados()).containsExactlyInAnyOrderEntriesOf(Map.of(
                TipoBatida.ENTRADA_1, LocalDateTime.of(DIA, LocalTime.of(8, 2, 31)),
                TipoBatida.SAIDA_1, LocalDateTime.of(DIA, LocalTime.of(12, 0, 15)),
                TipoBatida.ENTRADA_2, LocalDateTime.of(DIA, LocalTime.of(12, 59, 59))));
    }

    @Test
    @DisplayName("Batida já registrada pelo botão: o PDF não cria batida, mas é arquivado como prova dela")
    void arquivaPdfDeBatidaExistente() {
        new RegistrarBatidaUseCase(registros, classificador, motor, e -> { }, clock)
                .executar(DIA, LocalTime.of(8, 2, 50));

        Resultado r = importar("08:02:31").orElseThrow();

        assertThat(r.status()).isEqualTo(StatusImportacao.DUPLICADO);
        assertThat(r.mensagem()).contains("arquivado");
        assertThat(tiposArquivados()).containsOnlyKeys(TipoBatida.ENTRADA_1);
    }

    @Test
    @DisplayName("PDF importado antes do armazenamento é arquivado quando reaparece (sem nova batida)")
    void arquivamentoRetroativo() {
        importar("08:02:31", "legado");
        arquivos.salvos.clear(); // simula importação feita pela versão anterior, sem arquivo

        assertThat(importar("08:02:31", "legado")).isEmpty();
        assertThat(tiposArquivados()).containsOnlyKeys(TipoBatida.ENTRADA_1);
        assertThat(batidasDoDia().quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("PDFs inválidos, rejeitados ou duplicados por data/hora não são arquivados")
    void naoArquivaNaoImportados() {
        importar(null);
        importar("08:02:31");
        importar("08:02:31"); // outro arquivo, mesma batida

        assertThat(arquivos.salvos).hasSize(1);
    }
}
