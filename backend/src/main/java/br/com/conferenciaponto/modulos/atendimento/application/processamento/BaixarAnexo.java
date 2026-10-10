package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.Baixador;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.FalhaNoDownload;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.Sha256;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.TipoDetectado;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.TipoPeloConteudo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoParaBaixar;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConteudoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.OrigemDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Tarefa "baixar": baixa um anexo da conversa pelo link do PDF, confere o tipo pelos primeiros bytes, calcula o
 * sha-256, guarda na pasta do atendimento e apaga o link do banco. O que não dá para baixar (link vencido, host fora
 * da lista, arquivo grande demais) para só este anexo, com a mensagem; a pessoa pode enviar o arquivo à mão.
 */
@Component
public class BaixarAnexo implements ExecutorDeTarefa {

    private static final Logger log = LoggerFactory.getLogger(BaixarAnexo.class);
    private static final Set<SituacaoDoArquivo> A_BAIXAR = Set.of(SituacaoDoArquivo.AGUARDANDO, SituacaoDoArquivo.FALHOU,
            SituacaoDoArquivo.BAIXANDO);

    private final Arquivos arquivos;
    private final PastaDosAtendimentos pasta;
    private final Baixador baixador;
    private final PublicadorDeProgresso publicador;
    private final Clock clock;
    private final long tamanhoMaximo;
    private final long espacoMinimo;
    private final int paralelos;

    public BaixarAnexo(Arquivos arquivos, PastaDosAtendimentos pasta, Baixador baixador, PublicadorDeProgresso publicador,
                       Clock clock,
                       @Value("${atendimento.download.tamanho-maximo:2GB}") DataSize tamanhoMaximo,
                       @Value("${atendimento.armazenamento.espaco-minimo:200MB}") DataSize espacoMinimo,
                       @Value("${atendimento.download.paralelos:4}") int paralelos) {
        this.arquivos = arquivos;
        this.pasta = pasta;
        this.baixador = baixador;
        this.publicador = publicador;
        this.clock = clock;
        this.tamanhoMaximo = tamanhoMaximo.toBytes();
        this.espacoMinimo = espacoMinimo.toBytes();
        this.paralelos = paralelos;
    }

    @Override
    public TipoDeTarefa tipo() {
        return TipoDeTarefa.BAIXAR;
    }

    @Override
    public int limiteSimultaneo() {
        return paralelos;
    }

    @Override
    public ResultadoDaTarefa executar(Tarefa tarefa) {
        Optional<ArquivoParaBaixar> talvez = arquivos.paraBaixar(tarefa.arquivoId());
        if (talvez.isEmpty() || !A_BAIXAR.contains(talvez.get().situacao())) {
            return ResultadoDaTarefa.concluida(); // tirado, enviado à mão ou já baixado: nada a fazer
        }
        ArquivoParaBaixar anexo = talvez.get();
        if (anexo.url() == null) {
            return ResultadoDaTarefa.definitiva("SEM_LINK", "Este anexo não tem link para download. Envie o arquivo à mão.");
        }
        if (vencido(anexo, clock.instant())) {
            return ResultadoDaTarefa.definitiva("LINK_VENCIDO", ProcessarAtendimentos.LINK_VENCIDO);
        }
        if (pasta.espacoLivre() < espacoMinimo) {
            return discoCheio();
        }
        if (!arquivos.marcarBaixando(anexo.id())) {
            return ResultadoDaTarefa.concluida();
        }
        publicador.publicar(anexo.atendimentoId());

        Path parcial = pasta.parcialDoAnexo(anexo.atendimentoId(), anexo.id());
        long tamanho;
        try {
            tamanho = baixador.baixar(anexo.url(), parcial, tamanhoMaximo);
        } catch (FalhaNoDownload falha) {
            return resultadoDa(falha, anexo, parcial);
        }
        TipoDetectado tipo = TipoPeloConteudo.detectar(parcial, anexo.nome());
        String sha = Sha256.de(parcial);
        String caminho = pasta.guardarArquivo(anexo.atendimentoId(), parcial, OrigemDoArquivo.ANEXO_CONVERSA.prefixo(),
                anexo.ordem(), anexo.nome());
        ConteudoGuardado conteudo = new ConteudoGuardado(tamanho, sha, caminho, tipo.categoria(), tipo.tipoDeMidia(),
                tipo.suportado());
        if (!arquivos.concluirDownload(anexo.id(), conteudo, clock.instant(), naoSuportado(tipo))) {
            pasta.apagarArquivo(caminho); // enviado à mão ou tirado enquanto baixava: vale o que a pessoa fez
            log.info("Anexo {} do atendimento {} mudou durante o download; o baixado foi descartado", anexo.ordem(),
                    anexo.atendimentoId());
            return ResultadoDaTarefa.concluida();
        }
        log.info("Anexo {} do atendimento {} baixado: {} bytes, {}{}", anexo.ordem(), anexo.atendimentoId(), tamanho,
                tipo.tipoDeMidia(), tipo.suportado() ? "" : " (não suportado)");
        return ResultadoDaTarefa.concluida();
    }

    private ResultadoDaTarefa resultadoDa(FalhaNoDownload falha, ArquivoParaBaixar anexo, Path parcial) {
        if ("DISCO_CHEIO".equals(falha.codigo())) {
            return discoCheio();
        }
        if (falha.temporaria()) {
            return ResultadoDaTarefa.temporaria(falha.codigo(), falha.getMessage()); // o parcial fica: continua dali
        }
        pasta.descartar(parcial);
        boolean recusadoPorVencer = (falha.status() == 401 || falha.status() == 403)
                && anexo.validoAte() != null && vencido(anexo, clock.instant().plus(Duration.ofMinutes(5)));
        if (recusadoPorVencer) {
            return ResultadoDaTarefa.definitiva("LINK_VENCIDO", ProcessarAtendimentos.LINK_VENCIDO);
        }
        return ResultadoDaTarefa.definitiva(falha.codigo(), falha.getMessage());
    }

    private static boolean vencido(ArquivoParaBaixar anexo, Instant quando) {
        return anexo.validoAte() != null && !quando.isBefore(anexo.validoAte());
    }

    private static ResultadoDaTarefa discoCheio() {
        return ResultadoDaTarefa.pausar("DISCO_CHEIO", "O disco do servidor está cheio. O atendimento foi pausado: "
                + "libere espaço no computador do servidor (ou avise o administrador) e retome.");
    }

    static String naoSuportado(TipoDetectado tipo) {
        return "Não analisado: " + (tipo.descricao() == null ? "este tipo de arquivo" : tipo.descricao())
                + " não é um dos tipos que o Gemini lê. Exporte em PDF (ou converta para PNG, JPEG, MP3 ou MP4) e envie à mão.";
    }
}
