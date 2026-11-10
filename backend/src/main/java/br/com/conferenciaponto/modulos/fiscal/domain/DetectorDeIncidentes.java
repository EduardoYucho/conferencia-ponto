package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Compara as leituras de uma rodada com o estado anterior de cada alvo: registra as mudanças de situação e abre,
 * atualiza ou fecha os incidentes dos autorizadores. Sem informação não abre nem fecha incidente (é um buraco no
 * histórico, não uma queda nem uma volta).
 */
public final class DetectorDeIncidentes {

    /**
     * @param estados    o novo estado de cada alvo lido
     * @param mudancas   os alvos que trocaram de situação
     * @param incidentes os incidentes abertos ou atualizados nesta rodada e os que fecharam
     */
    public record Resultado(List<EstadoAtual> estados, List<Mudanca> mudancas, List<Incidente> incidentes) {
    }

    public Resultado aplicar(Map<String, EstadoAtual> anteriores, Map<String, Incidente> abertos, List<Leitura> leituras,
                             Instant agora) {
        List<EstadoAtual> estados = new ArrayList<>();
        List<Mudanca> mudancas = new ArrayList<>();
        List<Incidente> incidentes = new ArrayList<>();
        for (Leitura leitura : leituras) {
            String chave = leitura.alvo().chave();
            EstadoAtual anterior = anteriores.get(chave);
            Situacao agoraEsta = leitura.situacao();
            boolean mudou = anterior == null || anterior.situacao() != agoraEsta;
            Instant desde = mudou ? agora : anterior.desde();
            int ruins = agoraEsta.ruim() ? (anterior == null ? 0 : anterior.ruinsSeguidas()) + 1
                    : agoraEsta == Situacao.SEM_INFORMACAO && anterior != null ? anterior.ruinsSeguidas() : 0;
            estados.add(new EstadoAtual(leitura, agora, desde, ruins));
            if (mudou) {
                mudancas.add(new Mudanca(leitura.alvo(), anterior == null ? null : anterior.situacao(), agoraEsta,
                        leitura.detalhe(), agora));
            }
            if (leitura.alvo().ehGeral()) {
                Incidente aberto = abertos.get(chave);
                if (agoraEsta.ruim()) {
                    incidentes.add(aberto == null ? Incidente.abrir(leitura, agora) : aberto.comLeitura(leitura));
                } else if (agoraEsta == Situacao.NORMAL && aberto != null) {
                    incidentes.add(aberto.fechado(agora));
                }
            }
        }
        return new Resultado(estados, mudancas, incidentes);
    }
}
