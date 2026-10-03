package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.tela.DiaView;
import br.com.conferenciaponto.domain.model.TipoBatida;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Um dia pronto para a tela (Início e Meu ponto): situação, frase, cor, batidas com o nome usual, o que dá para
 * fazer e, para o detalhe, o registro completo com comprovantes e histórico de ajustes.
 */
public record DiaResponse(LocalDate data, String rotulo, String rotuloLongo, String relativo, boolean hoje,
                          boolean futuro, String situacao, String situacaoTexto, String tom, String descricao,
                          List<Batida> batidas, String faltando, Integer trabalhadoSegundos, Integer saldoSegundos,
                          int previstoSegundos, Marcador marcador, int lancadoSegundos,
                          List<LancamentoBancoResponse> lancamentos, boolean divergenciaRh, boolean ajustado,
                          boolean lancadoAMao, DiaView.Acoes acoes, List<String> filtros,
                          RegistroJornadaResponse registro, List<ComprovanteArquivoResponse> comprovantes,
                          List<AjusteResponse> ajustes) {

    public record Batida(TipoBatida tipo, String rotulo,
                         @JsonFormat(pattern = "HH:mm") LocalTime hora,
                         @JsonFormat(pattern = "HH:mm:ss") LocalTime real,
                         @JsonFormat(pattern = "HH:mm:ss") LocalTime considerado,
                         @JsonFormat(pattern = "HH:mm") LocalTime oficial,
                         Integer desvioSegundos, boolean tolerada, boolean ajustada, String nota, String tom,
                         UUID comprovanteId, String urlComprovante) {

        static Batida de(DiaView.Batida b) {
            return new Batida(b.tipo(), b.rotulo(), b.horario(), b.horario(), b.considerado(), b.oficial(),
                    b.desvioSegundos(), b.tolerada(), b.ajustada(), b.nota(), b.tom().name(), b.comprovanteId(),
                    b.urlComprovante());
        }
    }

    /** @param feriado marcação que vale para todos (só o administrador cadastra e remove) */
    public record Marcador(String tipo, String rotulo, String descricao, LocalDate inicio, LocalDate fim,
                           UUID ausenciaId, String abrangencia, boolean feriado) {

        static Marcador de(DiaView.Marcador m) {
            return m == null ? null : new Marcador(m.tipo(), m.rotulo(), m.descricao(), m.inicio(), m.fim(),
                    m.ausenciaId(), m.abrangencia(), m.feriado());
        }
    }

    public static DiaResponse de(DiaView d) {
        return new DiaResponse(d.data(), d.rotulo(), d.rotuloLongo(), d.relativo(), d.hoje(), d.futuro(),
                d.situacao().name(), d.situacaoTexto(), d.tom().name(), d.descricao(),
                d.batidas().stream().map(Batida::de).toList(), d.faltando(), d.trabalhadoSegundos(), d.saldoSegundos(),
                d.previstoSegundos(), Marcador.de(d.marcador()), d.lancadoSegundos(),
                d.lancamentos().stream().map(LancamentoBancoResponse::de).toList(), d.divergenciaRh(), d.ajustado(),
                d.lancadoAMao(), d.acoes(), d.filtros().stream().map(Enum::name).sorted().toList(),
                d.registro() == null ? null : RegistroJornadaResponse.de(d.registro()),
                d.comprovantes().stream().map(ComprovanteArquivoResponse::de).toList(),
                d.ajustes().stream().map(AjusteResponse::de).toList());
    }
}
