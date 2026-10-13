package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectMapper json;

    @Autowired
    public FilaDaRedacao(Saidas saidas, Tarefas tarefas, ConfiguracaoDaRedacao configuracao, ObjectMapper json) {
        this.saidas = saidas;
        this.tarefas = tarefas;
        this.configuracao = configuracao;
        this.json = json;
    }

    public FilaDaRedacao(Saidas saidas, Tarefas tarefas, ConfiguracaoDaRedacao configuracao) {
        this(saidas, tarefas, configuracao, new ObjectMapper());
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
    public int refazerAsQueFalharam(UUID atendimentoId, Instant agora) {
        int pedidas = 0;
        for (SaidaGuardada saida : saidas.ultimas(atendimentoId)) {
            if (saida.situacao() == SituacaoDaSaida.FALHOU && saida.tipo().disponivel()
                    && saidas.criar(atendimentoId, saida.tipo(), SEM_TEXTO, agora).isPresent()) {
                log.info("Atendimento {}: {} falhou e foi pedido de novo", atendimentoId, saida.tipo().codigo());
                pedidas++;
            }
        }
        return pedidas;
    }

    @Override
    public int refazerAsQueFicaramSem(UUID atendimentoId, UUID arquivoId, Instant agora) {
        int pedidas = 0;
        for (SaidaGuardada saida : saidas.ultimas(atendimentoId)) {
            if (saida.situacao() != SituacaoDaSaida.PRONTA || saida.htmlEditado() != null || !saida.tipo().disponivel()) {
                continue; // confirmado ou editado pela pessoa não muda sozinho
            }
            if (!semAnalise(saida).contains(arquivoId)) {
                continue;
            }
            if (saidas.criar(atendimentoId, saida.tipo(), TERMINADAS, agora).isPresent()) {
                log.info("Atendimento {}: {} v{} foi escrito sem a análise do arquivo {}, que chegou: versão nova pedida",
                        atendimentoId, saida.tipo().codigo(), saida.versao(), arquivoId);
                pedidas++;
            }
        }
        return pedidas;
    }

    private List<UUID> semAnalise(SaidaGuardada saida) {
        try {
            return EstruturaDaSaida.ler(json, saida.estrutura()).semAnalise();
        } catch (RuntimeException e) {
            return List.of(); // estrutura ilegível: não refaz sozinho
        }
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
