package br.com.conferenciaponto.modulos.atendimento.domain.linha;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.ItemDaConversa;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Lado;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.TipoDeItem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MontadorDaLinhaDoTempoTest {

    private static final Instant INICIO = Instant.parse("2026-10-09T13:00:00Z");
    private static final Instant FIM = Instant.parse("2026-10-09T13:40:00Z");
    private final MontadorDaLinhaDoTempo montador = new MontadorDaLinhaDoTempo(Duration.ofHours(1));

    private static ItemDaConversa mensagem(int ordem, Lado lado, String hora, String texto) {
        return new ItemDaConversa(ordem, TipoDeItem.MENSAGEM, lado, lado == Lado.CLIENTE ? "Cliente" : "Atendente",
                hora == null ? null : Instant.parse("2026-10-09T" + hora + ":00Z"), texto, List.of(), 1);
    }

    private static ItemDaConversa evento(int ordem, String hora, String texto) {
        return new ItemDaConversa(ordem, TipoDeItem.EVENTO, null, null, Instant.parse("2026-10-09T" + hora + ":00Z"), texto,
                List.of(), 1);
    }

    private static MaterialDoArquivo anexo(int ordem, Integer mensagem, String hora) {
        return new MaterialDoArquivo(UUID.randomUUID(), MaterialDoArquivo.ANEXO, ordem, "anexo" + ordem + ".jpeg", "imagem",
                mensagem, hora == null ? null : Instant.parse("2026-10-09T" + hora + ":00Z"), "Tela de pedidos",
                "analisado", "tela Pedidos");
    }

    private static MaterialDoArquivo extra(String origem, int ordem, String momento) {
        return new MaterialDoArquivo(UUID.randomUUID(), origem, ordem, origem + ordem, "audio", null,
                momento == null ? null : Instant.parse(momento), "Transcrição", "analisado", null);
    }

    private static List<String> ids(List<ItemDaLinha> itens) {
        return itens.stream().map(ItemDaLinha::id).toList();
    }

    @Test
    void mensagensNaOrdemDoPdfComCadaAnexoLogoDepoisDaMensagemEmQueFoiEnviado() {
        List<ItemDaConversa> conversa = List.of(mensagem(3, Lado.CLIENTE, "13:05", "segue o print"),
                mensagem(1, Lado.CLIENTE, "13:00", "bom dia"), evento(2, "13:01", "Atendimento iniciado"),
                mensagem(4, Lado.ATENDENTE, "13:06", "vou verificar"));
        LinhaDoTempo linha = montador.montar(conversa, List.of(anexo(2, 3, "13:05"), anexo(1, 3, "13:05")), INICIO, FIM, FIM);

        assertThat(ids(linha.itens())).containsExactly("M1", "M2", "M3", "A1", "A2", "M4");
        ItemDaLinha a1 = linha.item("A1").orElseThrow();
        assertThat(a1.mensagem()).isEqualTo("M3");
        assertThat(a1.lado()).isEqualTo("cliente");
        assertThat(a1.remetente()).isEqualTo("Cliente");
        assertThat(a1.texto()).isEqualTo("Tela de pedidos");
        assertThat(linha.item("M2").orElseThrow().tipo()).isEqualTo(ItemDaLinha.TipoDoItem.EVENTO);
        assertThat(linha.semHorario()).isEmpty();
    }

    @Test
    void extrasEntramPeloHorarioDepoisDasMensagensDoMesmoMinuto() {
        List<ItemDaConversa> conversa = List.of(mensagem(1, Lado.CLIENTE, "13:00", "a"), mensagem(2, Lado.ATENDENTE, "13:10", "b"),
                mensagem(3, Lado.CLIENTE, "13:10", "c"), mensagem(4, Lado.ATENDENTE, "13:20", "d"));
        MaterialDoArquivo ligacao = extra(MaterialDoArquivo.LIGACAO, 1, "2026-10-09T13:10:30Z");
        MaterialDoArquivo print = extra(MaterialDoArquivo.PRINT, 1, "2026-10-09T13:15:00Z");
        MaterialDoArquivo antes = extra(MaterialDoArquivo.PRINT, 2, "2026-10-09T12:30:00Z");

        LinhaDoTempo linha = montador.montar(conversa, List.of(print, ligacao, antes), INICIO, FIM, FIM);

        // empate de minuto: a mensagem vem antes; antes da primeira mensagem (dentro da margem), no começo
        assertThat(ids(linha.itens())).containsExactly("P2", "M1", "M2", "M3", "L1", "P1", "M4");
        assertThat(linha.item("L1").orElseThrow().momento()).isEqualTo(Instant.parse("2026-10-09T13:10:30Z"));
    }

    @Test
    void semDataOuComDataForaDoAtendimentoVaiParaSemHorario() {
        List<ItemDaConversa> conversa = List.of(mensagem(1, Lado.CLIENTE, "13:00", "a"), mensagem(2, Lado.ATENDENTE, "13:30", "b"));
        MaterialDoArquivo semData = extra(MaterialDoArquivo.LIGACAO, 2, null);
        MaterialDoArquivo deOntem = extra(MaterialDoArquivo.PRINT, 1, "2026-10-08T13:00:00Z");
        MaterialDoArquivo video = extra(MaterialDoArquivo.VIDEO, 1, "2026-10-09T18:00:00Z");
        MaterialDoArquivo dentroDaMargem = extra(MaterialDoArquivo.LIGACAO, 1, "2026-10-09T14:30:00Z");

        LinhaDoTempo linha = montador.montar(conversa, List.of(video, semData, deOntem, dentroDaMargem), INICIO, FIM, FIM);

        assertThat(ids(linha.itens())).containsExactly("M1", "M2", "L1");
        assertThat(ids(linha.semHorario())).as("na ordem: ligações, prints, vídeos").containsExactly("L2", "P1", "V1");
        assertThat(linha.todos()).hasSize(6);
    }

    @Test
    void anexoSemMensagemConhecidaEntraPeloHorario() {
        List<ItemDaConversa> conversa = List.of(mensagem(1, Lado.CLIENTE, "13:00", "a"), mensagem(2, Lado.ATENDENTE, "13:30", "b"));
        LinhaDoTempo linha = montador.montar(conversa, List.of(anexo(1, 99, "13:10"), anexo(2, null, null)), INICIO, FIM, FIM);

        assertThat(ids(linha.itens())).containsExactly("M1", "A1", "M2");
        assertThat(linha.item("A1").orElseThrow().mensagem()).isNull();
        assertThat(ids(linha.semHorario())).containsExactly("A2");
    }

    @Test
    void semCabecalhoAJanelaVemDasMensagensComHorario() {
        List<ItemDaConversa> conversa = List.of(mensagem(1, Lado.CLIENTE, null, "sem hora"), mensagem(2, Lado.CLIENTE, "13:00", "a"),
                mensagem(3, Lado.ATENDENTE, "13:30", "b"));
        MaterialDoArquivo ligacao = extra(MaterialDoArquivo.LIGACAO, 1, "2026-10-09T13:20:00Z");

        LinhaDoTempo linha = montador.montar(conversa, List.of(ligacao), null, null, FIM);

        assertThat(ids(linha.itens())).containsExactly("M1", "M2", "L1", "M3");
        assertThat(linha.versao()).isEqualTo(LinhaDoTempo.VERSAO);
        assertThat(linha.montadaEm()).isEqualTo(FIM);
    }
}
