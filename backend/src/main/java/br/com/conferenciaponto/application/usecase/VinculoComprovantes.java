package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Vínculo entre os PDFs arquivados e as batidas do dia. Um PDF comprova a batida de mesmo
 * horário ou, se ela foi registrada por outro meio (botão "bater ponto"), a batida a até
 * 1 minuto dele.
 */
final class VinculoComprovantes {

    static final Duration JANELA_DUPLICIDADE = Duration.ofMinutes(1);

    private VinculoComprovantes() {
    }

    /** Batida que o PDF comprova, pela posição atual das batidas. */
    static Optional<TipoBatida> tipoComprovado(Batidas batidas, LocalTime horario) {
        return batidas.posicaoDe(horario)
                .or(() -> batidas.batidaProxima(horario, JANELA_DUPLICIDADE).flatMap(batidas::posicaoDe));
    }

    /** Horários das batidas do dia que têm PDF (não podem ser alterados manualmente). */
    static Set<LocalTime> horariosComprovados(RegistroJornada registro, List<ComprovanteArquivado> arquivos) {
        Set<LocalTime> comprovados = new TreeSet<>();
        for (ComprovanteArquivado arquivo : arquivos) {
            tipoComprovado(registro.getBatidas(), arquivo.dataHoraBatida().toLocalTime())
                    .map(tipo -> registro.getBatidas().horario(tipo))
                    .ifPresent(comprovados::add);
        }
        return comprovados;
    }

    /** Depois que as batidas mudam de posição, atualiza a batida (ENTRADA_1...) de cada PDF. */
    static void reorganizar(RegistroJornada registro, ComprovanteArquivadoRepository arquivos) {
        for (ComprovanteArquivado arquivo : arquivos.listarPorRegistro(registro.getId())) {
            tipoComprovado(registro.getBatidas(), arquivo.dataHoraBatida().toLocalTime())
                    .filter(novo -> novo != arquivo.tipoBatida())
                    .ifPresent(novo -> arquivos.salvar(arquivo.comTipo(novo)));
        }
    }
}
