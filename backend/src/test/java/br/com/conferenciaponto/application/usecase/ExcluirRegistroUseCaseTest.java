package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExcluirRegistroUseCaseTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final ExcluirRegistroUseCase useCase = new ExcluirRegistroUseCase(registros, arquivos, e -> { });

    private RegistroJornada criarDia() {
        RegistroJornada registro = RegistroJornada.novo(DIA, TipoDia.UTIL);
        registro.registrarBatida(LocalTime.of(8, 2, 31), new MotorCalculoJornadaService());
        registros.salvar(registro);
        return registro;
    }

    @Test
    @DisplayName("Dia sem comprovante arquivado pode ser excluído")
    void excluiSemComprovante() {
        criarDia();
        useCase.executar(DIA);
        assertThat(registros.buscarPorData(DIA)).isEmpty();
    }

    @Test
    @DisplayName("Dia com comprovante PDF arquivado não pode ser excluído (trilha de auditoria)")
    void bloqueiaComComprovante() {
        RegistroJornada registro = criarDia();
        arquivos.salvar(new ComprovanteArquivado(UUID.randomUUID(), registro.getId(), "2026/09/comprovante_x.pdf",
                TipoBatida.ENTRADA_1, Instant.now(), LocalDateTime.of(DIA, LocalTime.of(8, 2, 31)),
                "comprovanteponto.pdf", "abc", 10));

        assertThatThrownBy(() -> useCase.executar(DIA))
                .isInstanceOf(ConflitoException.class)
                .extracting("codigo").isEqualTo("REGISTRO_COM_COMPROVANTES");
        assertThat(registros.buscarPorData(DIA)).isPresent();
    }
}
