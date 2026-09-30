package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor;

import java.time.Instant;
import java.util.List;

/**
 * Estado do monitor de comprovantes.
 *
 * @param habilitado      configuração {@code ponto.importacao-pdf.habilitado}
 * @param ativo           a pasta está sendo observada agora
 * @param situacao        INICIANDO, ATIVO, INDISPONIVEL, ENCERRADO ou DESABILITADO
 * @param mensagem        motivo quando a pasta está inacessível
 * @param ultimaVarredura última conferência completa da pasta
 */
public record MonitoramentoResponse(boolean habilitado, boolean ativo, String situacao, String diretorio,
                                    String mensagem, Instant ultimaVarredura,
                                    List<ComprovanteResponse> recentes) {

    public static MonitoramentoResponse de(EstadoMonitor estado, List<ComprovanteResponse> recentes) {
        return new MonitoramentoResponse(true, estado.ativo(), estado.situacao().name(), estado.diretorio(),
                estado.mensagem(), estado.ultimaVarredura(), recentes);
    }

    public static MonitoramentoResponse desabilitado(String diretorio, List<ComprovanteResponse> recentes) {
        return new MonitoramentoResponse(false, false, "DESABILITADO", diretorio, null, null, recentes);
    }
}
