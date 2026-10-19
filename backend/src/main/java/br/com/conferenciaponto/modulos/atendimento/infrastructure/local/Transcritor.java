package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import java.nio.file.Path;
import java.util.List;

/** Transforma a fala de um áudio (WAV mono de 16 kHz) em texto, com o início de cada trecho. */
interface Transcritor {

    /** Um trecho falado: quando começa no áudio e o que foi dito. */
    record Trecho(long inicioMs, String texto) {
    }

    /** O programa está instalado. */
    boolean disponivel();

    /**
     * @throws br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo ARQUIVO_FALHOU (não transcreveu),
     *         MODELO_INEXISTENTE (falta o modelo) ou INDISPONIVEL (demorou demais)
     */
    List<Trecho> transcrever(Path wav);
}
