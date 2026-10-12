package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.NovaImagem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** As saídas e as imagens delas, a linha do tempo e a finalização do atendimento, no PostgreSQL temporário. */
class SaidasJdbcTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");
    private static final Set<SituacaoDaSaida> SEM_TEXTO = EnumSet.of(SituacaoDaSaida.FALHOU);

    private final JdbcClient jdbc = PostgresDeTeste.jdbc();
    private final Saidas saidas = RepositoriosDeTeste.saidas();
    private final Atendimentos atendimentos = RepositoriosDeTeste.atendimentos();

    private UUID atendimento() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO atendimento.atendimento (id, usuario_id, chamado_digisac, conversa, versao_leitor,
                               apagar_arquivos_em, apagar_textos_em, situacao)
                        VALUES (?, ?, '1', '{}'::jsonb, 1, now(), now(), 'processando')
                        """).params(id, PostgresDeTeste.novoUsuario(true)).update();
        return id;
    }

    private UUID arquivo(UUID atendimento) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO atendimento.arquivo (id, atendimento_id, origem, ordem, nome_original) VALUES (?, ?, 'anexo_conversa', 1, 'a.png')")
                .params(id, atendimento).update();
        return id;
    }

    @Test
    void criaAVersaoSeguinteSoQuandoAUltimaPermite() {
        UUID atd = atendimento();
        UUID v1 = saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA).orElseThrow();
        assertThat(saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA)).as("a v1 ainda está pendente").isEmpty();
        assertThat(saidas.esperando(atd)).containsExactly(v1);

        assertThat(saidas.mudarSituacaoSe(v1, Set.of(SituacaoDaSaida.PENDENTE), SituacaoDaSaida.FALHOU, "sem chave")).isTrue();
        assertThat(saidas.mudarSituacaoSe(v1, Set.of(SituacaoDaSaida.PENDENTE), SituacaoDaSaida.REDIGINDO, null)).isFalse();
        UUID v2 = saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA).orElseThrow();

        SaidaGuardada ultima = saidas.ultima(atd, TipoDeSaida.RESUMO).orElseThrow();
        assertThat(ultima.id()).isEqualTo(v2);
        assertThat(ultima.versao()).isEqualTo(2);
        assertThat(ultima.situacao()).isEqualTo(SituacaoDaSaida.PENDENTE);
        assertThat(saidas.buscar(v1).orElseThrow().erroMensagem()).isEqualTo("sem chave");
        assertThat(saidas.ultimas(atd)).extracting(SaidaGuardada::id).containsExactly(v2);
    }

    @Test
    void doisPedidosAoMesmoTempoCriamUmaSo() throws Exception {
        UUID atd = atendimento();
        ExecutorService threads = Executors.newFixedThreadPool(8);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Optional<UUID>>> pedidos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pedidos.add(threads.submit(() -> {
                largada.await();
                return saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA);
            }));
        }
        largada.countDown();
        int criadas = 0;
        for (Future<Optional<UUID>> p : pedidos) {
            criadas += p.get().isPresent() ? 1 : 0;
        }
        threads.shutdown();
        assertThat(criadas).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM atendimento.saida WHERE atendimento_id = ?").param(atd).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void redacaoGravadaTrocaAsImagensEOTextoEditadoRenumeraNumaTransacao() {
        UUID atd = atendimento();
        UUID arq = arquivo(atd);
        UUID saida = saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA).orElseThrow();
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        List<NovaImagem> imagens = List.of(new NovaImagem(i1, 1, arq, "x/001.png", "primeira"),
                new NovaImagem(i2, 2, null, "x/002.png", "segunda"));

        assertThat(saidas.salvarRedacao(saida, "{\"versao\":1}", "<p>texto</p>", "Digisac: Maria : erro", "gemini-3.8-flash",
                1, 1000, 200, imagens)).isTrue();
        assertThat(saidas.salvarRedacao(saida, "{}", "<p>outro</p>", "x", "m", 1, 1, 1, List.of())).as("já pronta").isFalse();

        SaidaGuardada pronta = saidas.buscar(saida).orElseThrow();
        assertThat(pronta.situacao()).isEqualTo(SituacaoDaSaida.PRONTA);
        assertThat(pronta.htmlAtual()).isEqualTo("<p>texto</p>");
        assertThat(pronta.estrutura()).contains("\"versao\"");
        assertThat(pronta.tokensEntrada()).isEqualTo(1000);
        assertThat(saidas.imagens(saida)).extracting(ImagemDaSaida::id).containsExactly(i1, i2);

        // a 2 vira a 1 e a 1 sai do texto: os números trocam sem violar a unicidade
        saidas.salvarTexto(saida, "<p>editado</p>", List.of(new Saidas.ImagemNumerada(i2, 1, true, "a segunda"),
                new Saidas.ImagemNumerada(i1, 2, false, "primeira")));
        SaidaGuardada editada = saidas.buscar(saida).orElseThrow();
        assertThat(editada.htmlAtual()).isEqualTo("<p>editado</p>");
        assertThat(editada.html()).isEqualTo("<p>texto</p>");
        assertThat(saidas.imagens(saida)).extracting(ImagemDaSaida::id, ImagemDaSaida::numero, ImagemDaSaida::incluida,
                        ImagemDaSaida::legenda)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(i2, 1, true, "a segunda"),
                        org.assertj.core.groups.Tuple.tuple(i1, 2, false, "primeira"));

        saidas.salvarAssunto(saida, "Digisac: Maria : outro assunto");
        assertThat(saidas.buscar(saida).orElseThrow().assunto()).isEqualTo("Digisac: Maria : outro assunto");
        assertThat(saidas.tokens(atd)).containsExactly(1000L, 200L);
    }

    @Test
    void oAtendimentoSoFicaProntoSemTarefaNemSaidaEsperando() {
        UUID atd = atendimento();
        UUID saida = saidas.criar(atd, TipoDeSaida.RESUMO, SEM_TEXTO, AGORA).orElseThrow();
        assertThat(atendimentos.finalizarSeOcioso(atd, SituacaoDoAtendimento.PRONTO, AGORA)).as("saída pendente").isFalse();

        saidas.falharEsperando(atd, "linha do tempo falhou");
        RepositoriosDeTeste.tarefas().criar(TipoDeTarefa.LINHA_DO_TEMPO, atd, null, null, 3, AGORA);
        assertThat(atendimentos.finalizarSeOcioso(atd, SituacaoDoAtendimento.PRONTO, AGORA)).as("tarefa pendente").isFalse();

        jdbc.sql("UPDATE atendimento.tarefa SET situacao = 'concluida' WHERE atendimento_id = ?").param(atd).update();
        assertThat(atendimentos.finalizarSeOcioso(atd, SituacaoDoAtendimento.COM_FALHAS, AGORA)).isTrue();
        assertThat(atendimentos.estado(atd).orElseThrow().situacao()).isEqualTo(SituacaoDoAtendimento.COM_FALHAS);
        assertThat(saidas.buscar(saida).orElseThrow().erroMensagem()).isEqualTo("linha do tempo falhou");
        assertThat(atendimentos.finalizarSeOcioso(atd, SituacaoDoAtendimento.PRONTO, AGORA)).as("já não processava").isFalse();
    }

    @Test
    void camposDosChamadosEATelaDoCabecalho() {
        UUID atd = atendimento();
        assertThat(atendimentos.camposInformados(atd)).isEqualTo(CamposInformados.VAZIO);
        atendimentos.salvarCampos(atd, new CamposInformados("01/10/2026", true, "Z:\\Loja", "OTRS 1"), AGORA);
        assertThat(atendimentos.camposInformados(atd)).isEqualTo(new CamposInformados("01/10/2026", true, "Z:\\Loja", "OTRS 1"));
        atendimentos.salvarCampos(atd, CamposInformados.VAZIO, AGORA);
        assertThat(atendimentos.camposInformados(atd)).isEqualTo(CamposInformados.VAZIO);

        UUID saida = saidas.criar(atd, TipoDeSaida.DEV_ERRO, SEM_TEXTO, AGORA).orElseThrow();
        saidas.salvarRedacao(saida, "{\"versao\":1,\"telaFuncionalidade\":\"Notas\",\"avisos\":[\"a\"]}", "<p>x</p>",
                "Digisac: Maria : erro", "m", 1, 1, 1, List.of());
        saidas.salvarTela(saida, "Pedidos (VEN010)");
        assertThat(saidas.buscar(saida).orElseThrow().estrutura()).contains("\"telaFuncionalidade\": \"Pedidos (VEN010)\"")
                .contains("\"avisos\": [\"a\"]");
        saidas.salvarTela(saida, null);
        assertThat(saidas.buscar(saida).orElseThrow().estrutura()).contains("\"telaFuncionalidade\": null");
    }

    @Test
    void aLinhaDoTempoVaiEVoltaDoBanco() {
        UUID atd = atendimento();
        assertThat(atendimentos.linhaDoTempo(atd)).isEmpty();
        UUID arq = UUID.randomUUID();
        LinhaDoTempo linha = new LinhaDoTempo(1, AGORA, List.of(
                new ItemDaLinha("M1", ItemDaLinha.TipoDoItem.MENSAGEM, AGORA, "cliente", "Maria", "bom dia \"aspas\"", null,
                        null, null, null, null, null, null),
                new ItemDaLinha("A1", ItemDaLinha.TipoDoItem.ARQUIVO, AGORA, "cliente", "Maria", "Tela X", arq,
                        "anexo_conversa", "imagem", "print.png", "M1", "analisado", "tela X")),
                List.of(new ItemDaLinha("L1", ItemDaLinha.TipoDoItem.ARQUIVO, null, null, null, "Transcrição", null, "ligacao",
                        "audio", "lig.mp3", null, "sem_analise", null)));

        atendimentos.salvarLinhaDoTempo(atd, linha, AGORA);

        LinhaDoTempo lida = atendimentos.linhaDoTempo(atd).orElseThrow();
        assertThat(lida).isEqualTo(linha);
    }
}
