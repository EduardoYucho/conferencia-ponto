package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** As saídas no banco e as tarefas da redação na fila. */
@Component
public class FilaDaRedacao implements EtapaDeRedacao {

    private static final Logger log = LoggerFactory.getLogger(FilaDaRedacao.class);

    /** A linha do tempo não usa rede nem IA: se falhar, falha por um erro do sistema, e 3 tentativas bastam. */
    static final int TENTATIVAS_DA_LINHA = 3;
    private static final Set<SituacaoDaSaida> ESPERANDO = EnumSet.of(SituacaoDaSaida.PENDENTE, SituacaoDaSaida.REDIGINDO);
    private static final Set<SituacaoDaSaida> SEM_TEXTO = EnumSet.of(SituacaoDaSaida.FALHOU);
    private static final Set<SituacaoDaSaida> TERMINADAS = EnumSet.of(SituacaoDaSaida.PRONTA, SituacaoDaSaida.FALHOU,
            SituacaoDaSaida.CONFIRMADA);

    private final Saidas saidas;
    private final Tarefas tarefas;
    private final ConfiguracaoDaRedacao configuracao;

    public FilaDaRedacao(Saidas saidas, Tarefas tarefas, ConfiguracaoDaRedacao configuracao) {
        this.saidas = saidas;
        this.tarefas = tarefas;
        this.configuracao = configuracao;
    }

    @Override
    public int pedir(UUID atendimentoId, Set<TipoDeSaida> tipos, Instant agora) {
        int criadas = 0;
        for (TipoDeSaida tipo : tipos) {
            if (tipo.disponivel() && saidas.criar(atendimentoId, tipo, SEM_TEXTO, agora).isPresent()) {
                criadas++;
            }
        }
        return criadas;
    }

    @Override
    public boolean pedirNova(UUID atendimentoId, TipoDeSaida tipo, Instant agora) {
        return tipo.disponivel() && saidas.criar(atendimentoId, tipo, TERMINADAS, agora).isPresent();
    }

    @Override
    public boolean esperando(UUID atendimentoId) {
        return !saidas.esperando(atendimentoId).isEmpty();
    }

    @Override
    public boolean seguir(UUID atendimentoId, Instant agora) {
        if (!esperando(atendimentoId)) {
            return false;
        }
        if (tarefas.criar(TipoDeTarefa.LINHA_DO_TEMPO, atendimentoId, null, null, TENTATIVAS_DA_LINHA, agora)) {
            log.info("Atendimento {}: linha do tempo na fila para a redação", atendimentoId);
        }
        return true;
    }

    @Override
    public int redigirAsEsperando(UUID atendimentoId, Instant agora) {
        int criadas = 0;
        for (UUID saida : saidas.esperando(atendimentoId)) {
            if (tarefas.criar(TipoDeTarefa.REDIGIR, atendimentoId, null, saida, configuracao.tentativas(), agora)) {
                criadas++;
            }
        }
        return criadas;
    }

    @Override
    public void registrar(Tarefa tarefa, ResultadoDaTarefa resultado) {
        if (tarefa.tipo() == TipoDeTarefa.LINHA_DO_TEMPO) {
            if (resultado instanceof ResultadoDaTarefa.FalhaDefinitiva f) {
                saidas.falharEsperando(tarefa.atendimentoId(), "Não foi possível montar a linha do tempo do atendimento ("
                        + f.mensagem() + "). Clique em Gerar de novo.");
            }
            return;
        }
        if (tarefa.tipo() != TipoDeTarefa.REDIGIR || tarefa.saidaId() == null) {
            return;
        }
        if (resultado instanceof ResultadoDaTarefa.FalhaDefinitiva f) {
            saidas.mudarSituacaoSe(tarefa.saidaId(), ESPERANDO, SituacaoDaSaida.FALHOU, f.mensagem());
        } else if (resultado instanceof ResultadoDaTarefa.FalhaTemporaria t) {
            String mensagem = "GEMINI_LIMITE".equals(t.codigo()) ? t.mensagem()
                    : "Tentativa " + tarefa.tentativa() + " de " + tarefa.maxTentativas() + " não deu certo: " + t.mensagem()
                    + " Tentando de novo.";
            saidas.mudarSituacaoSe(tarefa.saidaId(), ESPERANDO, SituacaoDaSaida.PENDENTE, mensagem);
        } else if (resultado instanceof ResultadoDaTarefa.Pausar p) {
            saidas.mudarSituacaoSe(tarefa.saidaId(), ESPERANDO, SituacaoDaSaida.PENDENTE, p.mensagem());
        }
    }

    @Override
    public boolean comFalha(UUID atendimentoId) {
        return saidas.ultimas(atendimentoId).stream().anyMatch(s -> s.situacao() == SituacaoDaSaida.FALHOU);
    }

    @Override
    public List<EstadoDaSaida> estados(UUID atendimentoId) {
        return saidas.ultimas(atendimentoId).stream().map(EstadoDaSaida::de).toList();
    }

    @Override
    public long[] tokens(UUID atendimentoId) {
        return saidas.tokens(atendimentoId);
    }
}
