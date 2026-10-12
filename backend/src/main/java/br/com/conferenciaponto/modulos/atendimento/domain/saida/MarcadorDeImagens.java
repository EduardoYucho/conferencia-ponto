package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.nio.file.Path;
import java.util.List;

/** Desenha as marcações em vermelho numa cópia da imagem (o original não muda). */
public interface MarcadorDeImagens {

    /**
     * @param destino a cópia marcada (PNG, sem perder qualidade)
     * @return o tamanho da imagem, em pixels
     * @throws FalhaNaMarcacao quando a imagem não abre (formato que o servidor não lê, arquivo corrompido) ou a cópia
     *                         não pode ser gravada
     */
    Tamanho marcar(Path origem, Path destino, List<Marcacao> marcacoes);

    record Tamanho(int largura, int altura) {
    }

    class FalhaNaMarcacao extends RuntimeException {
        public FalhaNaMarcacao(String mensagem) {
            super(mensagem);
        }
    }
}
