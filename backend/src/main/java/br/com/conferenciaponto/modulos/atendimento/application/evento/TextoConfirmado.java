package br.com.conferenciaponto.modulos.atendimento.application.evento;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O autor confirmou um texto gerado e o enviou para a base de conhecimento, com o formulário que revisou. Sai dentro da
 * transação que marca a saída como confirmada: se quem recebe recusar (um campo longo demais, a base não liberada para
 * o autor), a saída continua pronta e nada fica pela metade.
 *
 * @param tipoDaSaida resumo, dev_erro ou dev_alteracao
 * @param html        o texto como vai para o sistema de chamados (com o cabeçalho e o rodapé, nos chamados), com
 *                    "Imagem N" no lugar de cada imagem
 * @param imagens     as imagens do texto que ainda estão no servidor, na ordem do texto
 */
public record TextoConfirmado(UUID atendimentoId, UUID saidaId, String tipoDaSaida, UUID autorId, String autorLogin,
                              Formulario formulario, String html, List<Imagem> imagens, Instant confirmadoEm) {

    public TextoConfirmado {
        imagens = imagens == null ? List.of() : List.copyOf(imagens);
    }

    /**
     * O registro como o autor revisou (os textos vêm como ele digitou; quem recebe confere e limpa).
     *
     * @param tipo       duvida, erro ou alteracao
     * @param situacao   resolvido_suporte, enviado_desenvolvimento, corrigido ou nao_sera_feito
     * @param chamadoDev o número do chamado no desenvolvimento, quando já existe
     */
    public record Formulario(String titulo, String tipo, String cliente, String contato, String modulo, String telaNome,
                             String telaCodigo, String sintoma, String mensagemErro, String causa, String resolucao,
                             String situacao, String chamadoDev) {
    }

    /**
     * Uma imagem do texto.
     *
     * @param numero  o "Imagem N" do texto
     * @param arquivo onde ela está no servidor agora (a base guarda uma cópia)
     */
    public record Imagem(int numero, Path arquivo, String legenda) {
    }
}
