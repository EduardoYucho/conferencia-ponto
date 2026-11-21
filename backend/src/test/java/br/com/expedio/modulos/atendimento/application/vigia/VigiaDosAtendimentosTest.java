package br.com.expedio.modulos.atendimento.application.vigia;

import br.com.expedio.application.evento.AvisoDeModulo;
import br.com.expedio.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.AtendimentoParaOVigia;
import br.com.expedio.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.expedio.modulos.atendimento.domain.atendimento.SituacaoDoAtendimento;
import br.com.expedio.modulos.atendimento.domain.fila.Tarefas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VigiaDosAtendimentosTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final long MINIMO = 200L * 1024 * 1024;

    private final Relogio relogio = new Relogio(LocalDateTime.of(2026, 11, 17, 10, 0).atZone(FUSO).toInstant());
    private final Atendimentos atendimentos = mock(Atendimentos.class);
    private final Tarefas tarefas = mock(Tarefas.class);
    private final ProcessarAtendimentos processamento = mock(ProcessarAtendimentos.class);
    private final PastaDosAtendimentos pasta = mock(PastaDosAtendimentos.class);
    private final List<AvisoDeModulo> avisos = new ArrayList<>();
    private VigiaDosAtendimentos vigia;

    @BeforeEach
    void preparar() {
        vigia = new VigiaDosAtendimentos(atendimentos, tarefas, processamento, pasta, MINIMO,
                e -> avisos.add((AvisoDeModulo) e), relogio);
        when(processamento.retomarSozinho(any())).thenReturn(true);
        when(pasta.espacoLivre()).thenReturn(10 * MINIMO);
    }

    private AtendimentoParaOVigia pausado(UUID id, Duration ha, String motivo) {
        Instant agora = relogio.instant();
        return new AtendimentoParaOVigia(id, UUID.randomUUID(), "20261009000001", SituacaoDoAtendimento.PAUSADO, motivo,
                agora.minus(Duration.ofDays(5)), agora.minus(ha), agora.plus(Duration.ofDays(25)), null, false);
    }

    private void lista(AtendimentoParaOVigia... itens) {
        when(atendimentos.paraOVigia(any())).thenReturn(List.of(itens));
    }

    // ------------------------------------------------------------------------------------------ retomar sozinho

    @Test
    void aIaLocalParadaERetomadaDepoisDe30MinutosEDepoisComEsperaDobrada() {
        UUID id = UUID.randomUUID();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("IA_LOCAL_PARADA"));

        lista(pausado(id, Duration.ofMinutes(29), "A IA local não respondeu."));
        assertThat(vigia.vigiarAgora().retomados()).isZero();

        lista(pausado(id, Duration.ofMinutes(31), "A IA local não respondeu."));
        assertThat(vigia.vigiarAgora().retomados()).isEqualTo(1);
        verify(processamento, times(1)).retomarSozinho(id);

        // pausou de novo (a IA continua fora): agora espera 1 hora
        lista(pausado(id, Duration.ofMinutes(59), "A IA local não respondeu."));
        assertThat(vigia.vigiarAgora().retomados()).isZero();
        lista(pausado(id, Duration.ofMinutes(61), "A IA local não respondeu."));
        assertThat(vigia.vigiarAgora().retomados()).isEqualTo(1);
        verify(processamento, times(2)).retomarSozinho(id);
        assertThat(avisos).isEmpty();
    }

    @Test
    void aEsperaNaoPassaDe8Horas() {
        UUID id = UUID.randomUUID();
        AtendimentoParaOVigia a = pausado(id, Duration.ZERO, null);
        Set<String> motivos = Set.of("GEMINI_INDISPONIVEL");
        assertThat(Duration.between(a.atualizadoEm(), vigia.quandoRetomar(a, motivos))).isEqualTo(Duration.ofMinutes(30));
        when(tarefas.motivosDaPausa(id)).thenReturn(motivos);
        for (int i = 0; i < 6; i++) { // 30 min, 1 h, 2 h, 4 h, 8 h, 8 h
            lista(pausado(id, Duration.ofHours(9), null));
            vigia.vigiarAgora();
        }
        assertThat(Duration.between(a.atualizadoEm(), vigia.quandoRetomar(a, motivos))).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void oQueSoAPessoaResolveNaoERetomado() {
        UUID chave = UUID.randomUUID();
        UUID misturado = UUID.randomUUID();
        UUID semMotivo = UUID.randomUUID();
        when(tarefas.motivosDaPausa(chave)).thenReturn(Set.of("CHAVE_GEMINI_RECUSADA"));
        when(tarefas.motivosDaPausa(misturado)).thenReturn(Set.of("IA_LOCAL_PARADA", "IA_LOCAL_SEM_MODELO"));
        when(tarefas.motivosDaPausa(semMotivo)).thenReturn(Set.of());
        lista(pausado(chave, Duration.ofHours(3), "chave"), pausado(misturado, Duration.ofHours(3), "modelo"),
                pausado(semMotivo, Duration.ofHours(3), null));

        assertThat(vigia.vigiarAgora().retomados()).isZero();
        verify(processamento, never()).retomarSozinho(any());
    }

    @Test
    void oDiscoCheioSoERetomadoComEspacoDeNovo() {
        UUID id = UUID.randomUUID();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("DISCO_CHEIO"));
        lista(pausado(id, Duration.ofHours(1), "O disco do servidor está cheio."));

        when(pasta.espacoLivre()).thenReturn(MINIMO - 1);
        assertThat(vigia.vigiarAgora().retomados()).isZero();
        when(pasta.espacoLivre()).thenReturn(MINIMO);
        assertThat(vigia.vigiarAgora().retomados()).isEqualTo(1);
    }

    @Test
    void aCotaDiariaERetomadaDepoisQueRenovaMeiaNoiteNoPacifico() {
        UUID id = UUID.randomUUID();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("GEMINI_COTA_DIARIA"));
        Instant pausadoEm = LocalDateTime.of(2026, 11, 17, 10, 0).atZone(FUSO).toInstant(); // 04:00 no Pacífico
        AtendimentoParaOVigia a = new AtendimentoParaOVigia(id, UUID.randomUUID(), "1", SituacaoDoAtendimento.PAUSADO,
                "A cota diária acabou.", pausadoEm, pausadoEm, pausadoEm.plus(Duration.ofDays(25)), null, false);
        lista(a);

        // a cota renova 18/11 00:00 no Pacífico = 05:00 em Brasília; com a folga, 05:10
        relogio.agora = LocalDateTime.of(2026, 11, 18, 5, 9).atZone(FUSO).toInstant();
        assertThat(vigia.vigiarAgora().retomados()).isZero();
        relogio.agora = LocalDateTime.of(2026, 11, 18, 5, 11).atZone(FUSO).toInstant();
        assertThat(vigia.vigiarAgora().retomados()).isEqualTo(1);
    }

    @Test
    void quemAPessoaRetomouAntesNaoContaComoRetomado() {
        UUID id = UUID.randomUUID();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("IA_LOCAL_PARADA"));
        when(processamento.retomarSozinho(id)).thenReturn(false); // já não estava pausado
        lista(pausado(id, Duration.ofHours(1), null));
        assertThat(vigia.vigiarAgora().retomados()).isZero();
    }

    // ------------------------------------------------------------------------------------------ avisos

    @Test
    void oPausadoHaMaisDe1DiaGanhaUmAvisoNoSinoUmaVezSo() {
        UUID id = UUID.randomUUID();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("CHAVE_GEMINI_RECUSADA"));
        lista(pausado(id, Duration.ofHours(23), "O Google recusou a chave."));
        assertThat(vigia.vigiarAgora().avisos()).isZero();

        AtendimentoParaOVigia a = pausado(id, Duration.ofHours(25), "O Google recusou a chave.");
        lista(a);
        assertThat(vigia.vigiarAgora().avisos()).isEqualTo(1);
        assertThat(vigia.vigiarAgora().avisos()).as("não repete").isZero();

        AvisoDeModulo aviso = avisos.get(0);
        assertThat(aviso.usuarioId()).isEqualTo(a.usuarioId());
        assertThat(aviso.chave()).isEqualTo("ATENDIMENTO_PARADO:" + id + ":2026-11-16");
        assertThat(aviso.titulo()).isEqualTo("Atendimento parado há mais de 1 dia");
        assertThat(aviso.mensagem()).contains("chamado 20261009000001", "pausado desde 16/11",
                "Motivo: O Google recusou a chave.", "Abra-o para retomar");
        assertThat(aviso.link()).isEqualTo("/atendimentos/" + id);
    }

    @Test
    void oNovoNuncaProcessadoTambemAvisa() {
        UUID id = UUID.randomUUID();
        Instant agora = relogio.instant();
        lista(new AtendimentoParaOVigia(id, UUID.randomUUID(), "77", SituacaoDoAtendimento.NOVO, null,
                agora.minus(Duration.ofDays(2)), agora.minus(Duration.ofDays(2)), agora.plus(Duration.ofDays(28)), null,
                false));
        assertThat(vigia.vigiarAgora().avisos()).isEqualTo(1);
        assertThat(avisos.get(0).mensagem()).contains("chamado 77 foi criado em 15/11 e ainda não foi processado");
    }

    @Test
    void comOsArquivosJaApagadosNaoRetomaEOAvisoDizOQueFazer() {
        UUID id = UUID.randomUUID();
        Instant agora = relogio.instant();
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("IA_LOCAL_PARADA"));
        lista(new AtendimentoParaOVigia(id, UUID.randomUUID(), "88", SituacaoDoAtendimento.PAUSADO, "A cota acabou.",
                agora.minus(Duration.ofDays(40)), agora.minus(Duration.ofDays(4)), agora.minus(Duration.ofDays(5)),
                agora.minus(Duration.ofDays(4)), false));

        assertThat(vigia.vigiarAgora()).isEqualTo(new VigiaDosAtendimentos.Volta(0, 1));
        verify(processamento, never()).retomarSozinho(any());
        assertThat(avisos.get(0).mensagem()).contains("ficou pausado e a limpeza de 30 dias já apagou os arquivos dele",
                "(em 13/11)", "gerar o texto só com a conversa");
    }

    @Test
    void tresDiasAntesDaLimpezaOAtendimentoSemTextoAvisa() {
        Instant agora = relogio.instant();
        UUID semTexto = UUID.randomUUID();
        UUID comTexto = UUID.randomUUID();
        UUID longe = UUID.randomUUID();
        UUID cancelado = UUID.randomUUID();
        lista(proximoDaLimpeza(semTexto, SituacaoDoAtendimento.COM_FALHAS, Duration.ofDays(2), false),
                proximoDaLimpeza(comTexto, SituacaoDoAtendimento.PRONTO, Duration.ofDays(2), true),
                proximoDaLimpeza(longe, SituacaoDoAtendimento.PRONTO, Duration.ofDays(4), false),
                proximoDaLimpeza(cancelado, SituacaoDoAtendimento.CANCELADO, Duration.ofDays(1), false));

        assertThat(vigia.vigiarAgora().avisos()).isEqualTo(1);
        AvisoDeModulo aviso = avisos.get(0);
        assertThat(aviso.chave()).isEqualTo("ATENDIMENTO_LIMPEZA:" + semTexto);
        assertThat(aviso.titulo()).isEqualTo("Os arquivos de um atendimento sem texto vão ser apagados");
        assertThat(aviso.mensagem()).contains("apaga os arquivos dele (anexos, ligações e vídeos) em 19/11");
        assertThat(agora).isNotNull();
    }

    private AtendimentoParaOVigia proximoDaLimpeza(UUID id, SituacaoDoAtendimento situacao, Duration falta, boolean texto) {
        Instant agora = relogio.instant();
        return new AtendimentoParaOVigia(id, UUID.randomUUID(), "99", situacao, null, agora.minus(Duration.ofDays(28)),
                agora.minus(Duration.ofDays(27)), agora.plus(falta), null, texto);
    }

    // ------------------------------------------------------------------------------------------ falhas

    @Test
    void umAtendimentoComProblemaNaoParaOsOutrosENadaEscapa() {
        UUID ruim = UUID.randomUUID();
        UUID bom = UUID.randomUUID();
        when(tarefas.motivosDaPausa(ruim)).thenThrow(new IllegalStateException("banco"));
        when(tarefas.motivosDaPausa(bom)).thenReturn(Set.of("IA_LOCAL_PARADA"));
        lista(pausado(ruim, Duration.ofHours(1), null), pausado(bom, Duration.ofHours(1), null));
        assertThat(vigia.vigiarAgora().retomados()).isEqualTo(1);

        when(atendimentos.paraOVigia(any())).thenThrow(new IllegalStateException("banco fora"));
        vigia.vigiar(); // não lança
    }

    @Test
    void oAvisoQueNaoSaiuETentadoDeNovoNaProximaVolta() {
        UUID id = UUID.randomUUID();
        List<Object> recebidos = new ArrayList<>();
        int[] falhas = {1};
        VigiaDosAtendimentos comFalha = new VigiaDosAtendimentos(atendimentos, tarefas, processamento, pasta, MINIMO,
                e -> {
                    if (falhas[0]-- > 0) {
                        throw new IllegalStateException("sino fora");
                    }
                    recebidos.add(e);
                }, relogio);
        when(tarefas.motivosDaPausa(id)).thenReturn(Set.of("CHAVE_GEMINI_RECUSADA"));
        lista(pausado(id, Duration.ofDays(2), null));

        assertThat(comFalha.vigiarAgora().avisos()).isZero();
        assertThat(comFalha.vigiarAgora().avisos()).isEqualTo(1);
        assertThat(recebidos).hasSize(1);
    }

    private static final class Relogio extends Clock {
        Instant agora;

        Relogio(Instant agora) {
            this.agora = agora;
        }

        @Override
        public ZoneId getZone() {
            return FUSO;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
