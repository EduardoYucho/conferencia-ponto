package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.usecase.ImportarComprovanteUseCase.Comprovante;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.HashSha256;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusImportacao;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static br.com.conferenciaponto.application.usecase.Fixtures.OUTRO;
import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O comprovante que entrou no ponto da pessoa errada: sai (com o histórico) e pode entrar no da pessoa certa. */
class DesfazerImportacaoUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate DIA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final ComprovantePontoRepositoryEmMemoria comprovantes = new ComprovantePontoRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final ArmazenamentoEmMemoria armazenamento = new ArmazenamentoEmMemoria();
    private final AjusteJornadaRepositoryEmMemoria historico = new AjusteJornadaRepositoryEmMemoria();
    private final ConferenciaMesRepositoryEmMemoria conferencias = new ConferenciaMesRepositoryEmMemoria();
    private final UsuarioRepositoryEmMemoria usuarios = UsuarioRepositoryEmMemoria.comTitular();
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(DIA.plusDays(1), LocalTime.of(10, 0), SP).toInstant(), SP);
    private final RegrasJornada regras = Fixtures.regras(new ClassificadorDiaService(data -> false));

    private final ImportarComprovanteUseCase importar = new ImportarComprovanteUseCase(registros, comprovantes, arquivos,
            armazenamento, regras, eventos::add, clock);
    private final DesfazerImportacaoUseCase useCase = new DesfazerImportacaoUseCase(arquivos, comprovantes, registros,
            historico, armazenamento, regras, new TravaDosMesesConferidos(conferencias, clock), importar, usuarios,
            eventos::add, clock);

    @BeforeEach
    void maria() {
        usuarios.salvar(new Usuario(OUTRO, "maria", "Maria", "h:senha", true, Acesso.usuario(), null));
    }

    private Comprovante pdf(String horario) {
        byte[] conteudo = ("%PDF-1.4 comprovante " + horario).getBytes();
        return new Comprovante("Comprovante " + horario + ".pdf", HashSha256.de(conteudo),
                Optional.of(LocalDateTime.of(DIA, LocalTime.parse(horario))), conteudo);
    }

    private ComprovanteArquivado arquivoDas(UUID usuario, String horario) {
        return registros.buscarPorData(usuario, DIA).map(r -> arquivos.listarPorRegistro(r.getId())).orElse(List.of())
                .stream().filter(a -> a.dataHoraBatida().toLocalTime().equals(LocalTime.parse(horario))).findFirst()
                .orElseThrow();
    }

    private List<LocalTime> batidas(UUID usuario) {
        return registros.buscarPorData(usuario, DIA).map(r -> r.getBatidas().horarios()).orElse(List.of());
    }

    @Test
    @DisplayName("Desfazer: a batida sai, os outros PDFs acompanham as colunas, fica no histórico e o PDF não volta pela pasta")
    void desfazer() {
        for (String h : List.of("08:00:10", "12:00:05", "12:30:00", "13:00:20")) {
            importar.executar(USUARIO, pdf(h)); // 12:30 é da Maria (caiu na pasta errada)
        }
        ComprovanteArquivado errado = arquivoDas(USUARIO, "12:30:00");

        DesfazerImportacaoUseCase.Resultado r = useCase.desfazer(USUARIO, errado.id(), "É da Maria", "eduardo");

        assertThat(r.mensagem()).isEqualTo("Batida das 12:30:00 de 28/09/2026 tirada (o comprovante não era deste ponto).");
        assertThat(batidas(USUARIO)).containsExactly(LocalTime.of(8, 0, 10), LocalTime.of(12, 0, 5), LocalTime.of(13, 0, 20));
        assertThat(arquivoDas(USUARIO, "13:00:20").tipoBatida()).as("a coluna do PDF acompanha").isEqualTo(TipoBatida.ENTRADA_2);
        assertThat(arquivos.buscarPorId(errado.id())).isEmpty();
        assertThat(armazenamento.desfeitos).containsExactly(errado.caminhoArquivo());
        assertThat(historico.salvos).singleElement().satisfies(a -> {
            assertThat(a.origem()).isEqualTo(AjusteJornada.Origem.DESFEITO);
            assertThat(a.justificativa()).isEqualTo("Importação desfeita: É da Maria");
            assertThat(a.antes()).hasSize(4);
            assertThat(a.depois()).hasSize(3);
        });
        assertThat(comprovantes.buscarPorHash(USUARIO, errado.hashSha256())).get()
                .satisfies(c -> assertThat(c.status()).isEqualTo(StatusImportacao.DESFEITO));

        // a pasta de quem desfez não importa de novo; a Maria importa normalmente
        assertThat(importar.executar(USUARIO, pdf("12:30:00"))).isEmpty();
        assertThat(batidas(USUARIO)).hasSize(3);
        assertThat(importar.executar(OUTRO, pdf("12:30:00"))).map(ImportarComprovanteUseCase.Resultado::status)
                .contains(StatusImportacao.IMPORTADO);
        assertThat(batidas(OUTRO)).containsExactly(LocalTime.of(12, 30));
    }

    @Test
    @DisplayName("Mover: sai de quem está e entra no ponto da pessoa certa numa gravação só")
    void mover() {
        importar.executar(USUARIO, pdf("08:00:10"));
        ComprovanteArquivado errado = arquivoDas(USUARIO, "08:00:10");

        DesfazerImportacaoUseCase.Resultado r = useCase.mover(errado.id(), OUTRO, "Caiu na pasta errada", "eduardo");

        assertThat(r.dia()).as("era a única batida: o dia saiu").isNull();
        assertThat(r.mensagem()).startsWith("Batida das 08:00:10 de 28/09/2026 tirada; o dia ficou sem batidas.")
                .contains("No ponto de Maria: Entrada 1 registrada às 08:00:10");
        assertThat(registros.buscarPorData(USUARIO, DIA)).isEmpty();
        assertThat(batidas(OUTRO)).containsExactly(LocalTime.of(8, 0, 10));
        assertThat(arquivoDas(OUTRO, "08:00:10").hashSha256()).isEqualTo(errado.hashSha256());
        assertThat(historico.salvos).singleElement().satisfies(a -> {
            assertThat(a.depois()).isEmpty();
            assertThat(a.justificativa()).isEqualTo("Comprovante movido para Maria: Caiu na pasta errada");
        });
    }

    @Test
    @DisplayName("Mover para quem não pode receber: recusado (na gravação de verdade, nada muda)")
    void moverRecusado() {
        importar.executar(USUARIO, pdf("08:00:10"));
        ComprovanteArquivado errado = arquivoDas(USUARIO, "08:00:10");

        // recusas antes de mexer em qualquer coisa
        assertThatThrownBy(() -> useCase.mover(errado.id(), USUARIO, "Caiu na pasta errada", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("já está no ponto desta pessoa");
        assertThatThrownBy(() -> useCase.mover(errado.id(), UUID.randomUUID(), "Caiu na pasta errada", "eduardo"))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        assertThat(batidas(USUARIO)).hasSize(1);

        // a Ana já tinha tirado este PDF do ponto dela (não é dela): ele não volta para ela, e a transação
        // (que aqui não existe) desfaz a saída do ponto de origem
        UUID ana = UUID.randomUUID();
        usuarios.salvar(new Usuario(ana, "ana", "Ana", "h", true, Acesso.usuario(), null));
        comprovantes.salvar(br.com.conferenciaponto.domain.model.ComprovanteImportado.novo(ana, "x.pdf",
                errado.hashSha256(), errado.dataHoraBatida(), StatusImportacao.DESFEITO, "não é meu"));
        assertThatThrownBy(() -> useCase.mover(errado.id(), ana, "Caiu na pasta errada", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("Ana já tirou este comprovante");
        assertThat(batidas(ana)).isEmpty();
    }

    @Test
    @DisplayName("PDF que só comprovava uma batida do botão: o PDF sai, a batida fica")
    void soOPdf() {
        importar.executar(USUARIO, pdf("08:00:10"));
        var registro = registros.buscarPorData(USUARIO, DIA).orElseThrow();
        // a 2ª batida veio do botão; o PDF de 12:00:30 chegou depois e ficou como prova dela (DUPLICADO)
        registro.incluirBatida(LocalTime.of(12, 0), regras.motor(USUARIO, DIA));
        registros.salvar(registro);
        importar.executar(USUARIO, pdf("12:00:30"));
        ComprovanteArquivado prova = arquivoDas(USUARIO, "12:00:30");

        DesfazerImportacaoUseCase.Resultado r = useCase.desfazer(USUARIO, prova.id(), "Não é o meu", "eduardo");

        assertThat(r.mensagem()).isEqualTo("Comprovante de 28/09/2026 12:00:30 tirado do dia; a batida das 12:00:00 fica (não veio dele).");
        assertThat(batidas(USUARIO)).containsExactly(LocalTime.of(8, 0, 10), LocalTime.of(12, 0));
        assertThat(historico.salvos).isEmpty();
        assertThat(comprovantes.buscarPorHash(USUARIO, prova.hashSha256())).get()
                .satisfies(c -> assertThat(c.status()).isEqualTo(StatusImportacao.DESFEITO));
    }

    @Test
    @DisplayName("Sem motivo, de outra pessoa ou em mês conferido: recusado, nada muda")
    void recusas() {
        importar.executar(USUARIO, pdf("08:00:10"));
        ComprovanteArquivado arquivo = arquivoDas(USUARIO, "08:00:10");

        assertThatThrownBy(() -> useCase.desfazer(USUARIO, arquivo.id(), " ", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("por que");
        assertThatThrownBy(() -> useCase.desfazer(OUTRO, arquivo.id(), "Não é meu", "maria"))
                .as("o de outra pessoa nem aparece").isInstanceOf(RecursoNaoEncontradoException.class);
        conferencias.salvar(ConferenciaMes.nova(USUARIO, YearMonth.from(DIA), "carla", null,
                SaldoMensal.vazio(2026, 9, 0), Instant.EPOCH));
        assertThatThrownBy(() -> useCase.desfazer(USUARIO, arquivo.id(), "Não é meu", "eduardo"))
                .isInstanceOf(ConflitoException.class)
                .satisfies(e -> assertThat(((DominioException) e).getCodigo()).isEqualTo("MES_CONFERIDO"));
        assertThat(batidas(USUARIO)).hasSize(1);
        assertThat(arquivos.buscarPorId(arquivo.id())).isPresent();
        assertThat(armazenamento.desfeitos).isEmpty();
    }
}
