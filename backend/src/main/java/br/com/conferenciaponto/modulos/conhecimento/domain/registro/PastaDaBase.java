package br.com.conferenciaponto.modulos.conhecimento.domain.registro;

import java.nio.file.Path;
import java.util.UUID;

/** As imagens dos registros, guardadas fora da pasta dos atendimentos (que a retenção apaga). */
public interface PastaDaBase {

    /**
     * Copia a imagem para a pasta do registro como Imagem_NN (com a extensão do original).
     *
     * @return o caminho guardado no banco (relativo à pasta dos módulos)
     */
    String copiar(UUID registroId, int numero, Path origem);

    /** O arquivo de um caminho guardado no banco (sempre dentro da pasta da base). */
    Path local(String caminho);

    /** Apaga a pasta do registro (não falha se ela não existe). */
    void apagar(UUID registroId);
}
