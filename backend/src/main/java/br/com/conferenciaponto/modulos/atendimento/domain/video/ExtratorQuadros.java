package br.com.conferenciaponto.modulos.atendimento.domain.video;

import java.nio.file.Path;

/**
 * Tira um quadro (uma imagem PNG, na resolução original) de um vídeo num instante exato. O programa que faz isso (o
 * ffmpeg) é opcional: sem ele, o vídeo é analisado mesmo assim e os passos ficam sem quadro, com aviso na tela.
 */
public interface ExtratorQuadros {

    /**
     * Se o programa está disponível agora (quem não o encontrou confere de novo de tempos em tempos: instalar o
     * ffmpeg não exige reiniciar o sistema).
     *
     * @param versao  a versão encontrada (ex.: 7.1-full_build-www.gyan.dev), ou null
     * @param caminho onde ele está, ou null
     */
    record Situacao(boolean disponivel, String versao, String caminho) {

        public static final Situacao AUSENTE = new Situacao(false, null, null);
    }

    Situacao situacao();

    /**
     * Grava em {@code destino} o quadro do instante (a gravação é atômica: ou o arquivo está inteiro, ou não existe).
     *
     * @throws FalhaNoQuadro FFMPEG_AUSENTE (o programa não está instalado) ou FFMPEG_FALHOU (o vídeo não abriu, o
     *                       instante passa do fim, o programa demorou demais)
     */
    void extrair(Path video, long instanteMs, Path destino);
}
