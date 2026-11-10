package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Onde o monitor guarda o estado, o histórico e as rodadas (schema fiscal). */
public interface RegistroDoMonitor {

    /**
     * O aviso de manutenção publicado num painel ("Não há manutenção prevista para o próximo Domingo"); sem texto, o
     * painel foi lido e não tem aviso (o anterior sai).
     */
    record Aviso(Documento documento, String texto, Instant desde, Instant vistoEm) {
    }

    /** A última tentativa de uma fonte. */
    record SituacaoDaFonte(String nome, Instant ultimaTentativa, Instant ultimoSucesso, String erro) {

        public boolean comProblema() {
            return erro != null;
        }
    }

    /** Uma coleta. */
    record Rodada(Instant inicio, Instant fim, int leituras, int fontesComProblema, boolean semInternet) {
    }

    /** Tudo o que uma rodada grava, de uma vez (numa transação). */
    record RodadaConcluida(Rodada rodada, List<EstadoAtual> estados, List<Mudanca> mudancas, List<Incidente> incidentes,
                           List<Leitura> gerais, List<SituacaoDaFonte> fontes, List<Aviso> avisos) {
    }

    /** Disponibilidade de um autorizador num período (leituras sem informação ficam de fora da conta). */
    record Disponibilidade(Documento documento, String autorizador, int leituras, int ruins, int semInformacao,
                           Integer tempoMedioMs, Integer tempoMaxMs) {

        /** De 0 a 100; vazio sem nenhuma leitura com informação. */
        public Double percentual() {
            int validas = leituras - semInformacao;
            return validas <= 0 ? null : Math.round((validas - ruins) * 1000.0 / validas) / 10.0;
        }
    }

    Map<String, EstadoAtual> estados();

    Map<String, Incidente> incidentesAbertos();

    void gravar(RodadaConcluida rodada);

    /** Os incidentes que começaram depois de {@code desde} ou ainda estavam abertos, do mais recente para o mais antigo. */
    List<Incidente> incidentesDesde(Instant desde);

    List<Disponibilidade> disponibilidadeDesde(Instant desde);

    Optional<Rodada> ultimaRodada();

    List<SituacaoDaFonte> fontes();

    List<Aviso> avisos();

    /** Apaga o histórico antigo (os incidentes ficam para sempre). */
    void limpar(Instant resumosAntesDe, Instant mudancasAntesDe, Instant rodadasAntesDe);
}
