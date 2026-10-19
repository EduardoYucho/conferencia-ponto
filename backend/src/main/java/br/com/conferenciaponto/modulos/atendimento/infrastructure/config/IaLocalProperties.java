package br.com.conferenciaponto.modulos.atendimento.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * A IA local ({@code atendimento.ia.local} no atendimento.yml): o Ollama roda o modelo neste computador (imagens,
 * documentos, quadros dos vídeos e os textos) e o Whisper transcreve os áudios. Sem chave, sem cota e sem custo; nada
 * sai do computador.
 *
 * @param url              o Ollama (o serviço que ele deixa aberto na porta 11434)
 * @param modelo           o modelo que lê os arquivos e escreve os textos (ex.: gemma4:12b)
 * @param modeloVetor      o modelo dos vetores da pesquisa por significado (ex.: embeddinggemma)
 * @param contexto         quantos tokens o modelo lê de uma vez (o material de um atendimento longo cabe em 24 mil)
 * @param tempoConexao     espera para abrir a conexão com o Ollama
 * @param tempoLimite      espera pela resposta de um pedido (o primeiro do dia carrega o modelo na placa)
 * @param manterCarregado  quanto tempo o modelo fica na placa de vídeo depois do último uso (depois, ela fica livre
 *                         para os jogos)
 * @param whisper          a transcrição dos áudios
 * @param video            os quadros dos vídeos
 * @param pdf              as páginas dos documentos
 * @param placa            a espera pela placa de vídeo livre
 */
@ConfigurationProperties(prefix = "atendimento.ia.local")
public record IaLocalProperties(
        @DefaultValue("http://localhost:11434") URI url,
        @DefaultValue("gemma4:12b") String modelo,
        @DefaultValue("embeddinggemma") String modeloVetor,
        @DefaultValue("24576") int contexto,
        @DefaultValue("10s") Duration tempoConexao,
        @DefaultValue("10m") Duration tempoLimite,
        @DefaultValue("2m") Duration manterCarregado,
        @DefaultValue Whisper whisper,
        @DefaultValue Video video,
        @DefaultValue Pdf pdf,
        @DefaultValue Placa placa) {

    /**
     * @param executavel o whisper-cli.exe; vazio = a pasta ia-local da Conferência de Ponto
     * @param modelo     o modelo do Whisper (ggml); vazio = o large-v3-turbo da mesma pasta
     * @param idioma     o idioma dos áudios
     * @param tempoLimite espera por uma transcrição (uma ligação longa demora)
     */
    public record Whisper(@DefaultValue("") String executavel, @DefaultValue("") String modelo,
                          @DefaultValue("pt") String idioma, @DefaultValue("10m") Duration tempoLimite) {

        public Path executavelOuPadrao() {
            return executavel == null || executavel.isBlank()
                    ? pastaPadrao().resolve("whisper").resolve("Release").resolve("whisper-cli.exe") : Path.of(executavel.strip());
        }

        public Path modeloOuPadrao() {
            return modelo == null || modelo.isBlank()
                    ? pastaPadrao().resolve("modelos").resolve("ggml-large-v3-turbo.bin") : Path.of(modelo.strip());
        }
    }

    /** @param quadros quantos quadros do vídeo o modelo olha, espalhados do começo ao fim */
    public record Video(@DefaultValue("16") int quadros) {
    }

    /**
     * @param paginas quantas páginas do documento o modelo olha como imagem (o texto vai inteiro)
     * @param caracteres o máximo do texto do documento que vai no pedido
     */
    public record Pdf(@DefaultValue("4") int paginas, @DefaultValue("40000") int caracteres) {
    }

    /**
     * @param esperarLivre      com a placa ocupada (um jogo aberto), o atendimento espera ela ficar livre, em vez de
     *                          rodar devagar e pesar no jogo
     * @param memoriaNecessaria com o modelo fora da placa: quanto precisa estar livre nela para ele entrar
     * @param usoMaximo         com o modelo na placa e a IA parada: acima deste uso (0 a 100), outro programa está
     *                          usando a placa (um jogo), e a IA espera
     * @param intervalo         de quanto em quanto tempo confere de novo
     * @param nvidiaSmi         o programa que diz quanto da placa está em uso (vem com o driver da NVIDIA)
     */
    public record Placa(@DefaultValue("true") boolean esperarLivre, @DefaultValue("8GB") DataSize memoriaNecessaria,
                        @DefaultValue("60") int usoMaximo, @DefaultValue("2m") Duration intervalo,
                        @DefaultValue("nvidia-smi") String nvidiaSmi) {
    }

    /** A pasta ia-local, ao lado das outras da Conferência de Ponto. */
    public static Path pastaPadrao() {
        return Path.of(System.getProperty("user.home"), ".conferencia-ponto", "ia-local");
    }
}
