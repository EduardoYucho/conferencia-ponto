package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import java.util.Locale;
import java.util.Optional;

/** As três formas de copiar o texto para o sistema de chamados (a validação mostra qual o editor aceita). */
public enum ModoDeCopia {
    /** HTML formatado, com as imagens embutidas logo depois do "Imagem N". */
    COM_IMAGENS,
    /** HTML formatado com "Imagem N" no lugar das imagens, para anexar os arquivos numerados. */
    SO_TEXTO,
    /** O HTML puro (o mesmo do "só o texto"), para colar pelo botão de código-fonte do editor. */
    CODIGO_FONTE;

    public static Optional<ModoDeCopia> doCodigo(String codigo) {
        if (codigo == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(codigo.strip().toUpperCase(Locale.ROOT).replace('-', '_')));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
