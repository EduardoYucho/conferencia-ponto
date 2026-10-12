package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.MaterialDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ConferenciaDoTexto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * A linha do tempo em texto, como vai para o Gemini redigir: cada item com o identificador entre colchetes (é o que
 * o texto cita nas fontes), o momento, quem falou e o conteúdo; depois, o material sem horário e a lista das imagens
 * que o texto pode usar.
 */
final class MaterialDaRedacao {

    private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private MaterialDaRedacao() {
    }

    static String texto(String chamado, String contato, Instant inicio, Instant fim, LinhaDoTempo linha,
                        List<ConferenciaDoTexto.ImagemDisponivel> imagens, ZoneId fuso) {
        StringBuilder t = new StringBuilder();
        t.append("Atendimento do chamado ").append(chamado == null ? "não identificado" : chamado)
                .append(" no Digisac, com o contato \"").append(contato == null || contato.isBlank() ? "não identificado" : contato)
                .append('"');
        if (inicio != null) {
            t.append(", de ").append(DATA_E_HORA.format(inicio.atZone(fuso)));
            if (fim != null) {
                t.append(" a ").append(DATA_E_HORA.format(fim.atZone(fuso)));
            }
        }
        t.append(".\n\nMaterial, em ordem (cada item com o identificador entre colchetes):\n");
        if (linha.itens().isEmpty()) {
            t.append("\n(nenhuma mensagem)\n");
        }
        for (ItemDaLinha item : linha.itens()) {
            t.append('\n').append(item(item, fuso)).append('\n');
        }
        if (!linha.semHorario().isEmpty()) {
            t.append("\nMaterial sem horário (encaixe pelo conteúdo e diga no texto que o momento não é conhecido):\n");
            for (ItemDaLinha item : linha.semHorario()) {
                t.append('\n').append(item(item, fuso)).append('\n');
            }
        }
        t.append("\nImagens disponíveis para o texto (em \"imagens\", use só estes identificadores");
        if (imagens.stream().anyMatch(i -> i.instanteMs() != null)) {
            t.append("; os quadros dos vídeos, como V1@02:15, mostram a tela no instante do passo e ilustram esse passo");
        }
        t.append("):\n");
        if (imagens.isEmpty()) {
            t.append("Nenhuma: deixe \"imagens\" vazio em todos os trechos.\n");
        }
        for (ConferenciaDoTexto.ImagemDisponivel imagem : imagens) {
            t.append("- ").append(imagem.id()).append(": ").append(imagem.legendaPadrao() == null ? "imagem" : imagem.legendaPadrao())
                    .append('\n');
        }
        return t.toString().stripTrailing();
    }

    private static String item(ItemDaLinha item, ZoneId fuso) {
        StringBuilder t = new StringBuilder("[").append(item.id()).append("] ");
        if (item.momento() != null) {
            t.append(DIA_E_HORA.format(item.momento().atZone(fuso))).append(" · ");
        }
        switch (item.tipo()) {
            case MENSAGEM -> t.append("mensagem do ").append("cliente".equals(item.lado()) ? "cliente" : "atendente")
                    .append(item.remetente() == null || item.remetente().isBlank() ? "" : " (" + item.remetente() + ")");
            case EVENTO -> t.append("evento do sistema");
            case ARQUIVO -> t.append(arquivo(item));
        }
        t.append(":\n").append(item.texto().isBlank() ? "(sem texto)" : item.texto().strip());
        return t.toString();
    }

    private static String arquivo(ItemDaLinha item) {
        String categoria = switch (item.categoria() == null ? "" : item.categoria()) {
            case "imagem" -> "imagem";
            case "audio" -> "áudio";
            case "video" -> "vídeo";
            case "documento" -> "documento";
            default -> "arquivo";
        };
        String origem = switch (item.origem() == null ? "" : item.origem()) {
            case MaterialDoArquivo.ANEXO -> categoria + ("imagem".equals(categoria) ? " enviada" : " enviado") + " na conversa"
                    + ("cliente".equals(item.lado()) ? " pelo cliente" : "atendente".equals(item.lado()) ? " pelo atendente" : "")
                    + (item.mensagem() == null ? "" : ", na mensagem " + item.mensagem());
            case MaterialDoArquivo.LIGACAO -> "gravação de ligação (" + categoria + ")";
            case MaterialDoArquivo.VIDEO -> "vídeo de reprodução";
            case MaterialDoArquivo.PRINT -> "print enviado pelo atendente (" + categoria + ")";
            default -> categoria;
        };
        return origem + (item.nome() == null ? "" : ", arquivo \"" + item.nome() + "\"");
    }
}
