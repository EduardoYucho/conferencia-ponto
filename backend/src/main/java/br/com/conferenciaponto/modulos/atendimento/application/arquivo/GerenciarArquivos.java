package br.com.conferenciaponto.modulos.atendimento.application.arquivo;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.PublicadorDeProgresso;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.Sha256;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.TipoDetectado;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.TipoPeloConteudo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ConteudoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.NovoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.OrigemDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Os arquivos que a pessoa envia: ligações, o vídeo de reprodução e prints (extras), e o envio à mão de um anexo
 * da conversa que não pôde ser baixado; e tirar um arquivo. O envio chega em fluxo, direto para o disco; o tipo é
 * conferido pelos primeiros bytes, e o nome nunca vira caminho sem ser saneado.
 */
@Service
public class GerenciarArquivos {

    private static final Logger log = LoggerFactory.getLogger(GerenciarArquivos.class);

    /** As extensões aceitas no envio (as que o Gemini lê). */
    static final Map<CategoriaDoArquivo, Set<String>> ACEITOS = Map.of(
            CategoriaDoArquivo.IMAGEM, Set.of("png", "jpg", "jpeg", "webp", "heic", "heif"),
            CategoriaDoArquivo.AUDIO, Set.of("mp3", "m4a", "wav", "ogg", "oga", "opus", "aac", "flac"),
            CategoriaDoArquivo.VIDEO, Set.of("mp4", "mov", "webm", "avi", "mkv"),
            CategoriaDoArquivo.DOCUMENTO, Set.of("pdf", "txt"));
    private static final String TIPOS_ACEITOS = "imagem (png, jpeg, webp, heic), áudio (mp3, m4a, wav, ogg, opus, aac, "
            + "flac), vídeo (mp4, mov, webm, avi, mkv) ou documento (pdf, txt). Word e Excel: exporte em PDF";

    private final Atendimentos atendimentos;
    private final Arquivos arquivos;
    private final Tarefas tarefas;
    private final PastaDosAtendimentos pasta;
    private final ProcessarAtendimentos processamento;
    private final PublicadorDeProgresso publicador;
    private final Clock clock;
    private final Map<CategoriaDoArquivo, Long> limites;
    private final long espacoMinimo;
    /** A ordem de cada envio é escolhida e gravada de uma vez (dois envios ao mesmo tempo não pegam a mesma). */
    private final Object travaDaOrdem = new Object();

    public GerenciarArquivos(Atendimentos atendimentos, Arquivos arquivos, Tarefas tarefas, PastaDosAtendimentos pasta,
                             ProcessarAtendimentos processamento, PublicadorDeProgresso publicador, Clock clock,
                             @Value("${atendimento.envio.tamanho-maximo.imagem:50MB}") DataSize imagem,
                             @Value("${atendimento.envio.tamanho-maximo.audio:500MB}") DataSize audio,
                             @Value("${atendimento.envio.tamanho-maximo.video:2GB}") DataSize video,
                             @Value("${atendimento.envio.tamanho-maximo.documento:50MB}") DataSize documento,
                             @Value("${atendimento.armazenamento.espaco-minimo:200MB}") DataSize espacoMinimo) {
        this.atendimentos = atendimentos;
        this.arquivos = arquivos;
        this.tarefas = tarefas;
        this.pasta = pasta;
        this.processamento = processamento;
        this.publicador = publicador;
        this.clock = clock;
        this.limites = Map.of(CategoriaDoArquivo.IMAGEM, imagem.toBytes(), CategoriaDoArquivo.AUDIO, audio.toBytes(),
                CategoriaDoArquivo.VIDEO, video.toBytes(), CategoriaDoArquivo.DOCUMENTO, documento.toBytes());
        this.espacoMinimo = espacoMinimo.toBytes();
    }

    /**
     * Uma ligação, o vídeo de reprodução ou um print.
     *
     * @param modificadoEm a data de modificação do arquivo no computador da pessoa (milissegundos ou ISO-8601),
     *                     usada na linha do tempo; inválida ou ausente = sem horário
     */
    public ArquivoView enviarExtra(Usuario usuario, UUID atendimentoId, String origemInformada, String nome,
                                   String modificadoEm, InputStream corpo, long tamanhoInformado) {
        doDono(usuario, atendimentoId);
        OrigemDoArquivo origem = OrigemDoArquivo.doCodigo(origemInformada)
                .filter(o -> o != OrigemDoArquivo.ANEXO_CONVERSA)
                .orElseThrow(() -> new RegraNegocioException("ORIGEM_INVALIDA",
                        "Diga se o arquivo é uma ligação, o vídeo de reprodução ou um print."));
        String nomeLimpo = limparNome(nome);
        CategoriaDoArquivo pelaExtensao = categoriaAceita(nomeLimpo);
        Recebido recebido = receber(corpo, tamanhoInformado, pelaExtensao, nomeLimpo);
        try {
            Instant agora = clock.instant();
            ArquivoView view;
            synchronized (travaDaOrdem) {
                int ordem = arquivos.proximaOrdem(atendimentoId, origem);
                String caminho = pasta.guardarArquivo(atendimentoId, recebido.arquivo(), origem.prefixo(), ordem, nomeLimpo);
                UUID id = UUID.randomUUID();
                ConteudoGuardado conteudo = recebido.conteudo(caminho);
                if (!arquivos.inserir(new NovoArquivo(id, atendimentoId, origem, ordem, nomeLimpo, conteudo,
                        momento(modificadoEm, agora), agora))) {
                    pasta.apagarArquivo(caminho);
                    throw new ConflitoException("ENVIO_SIMULTANEO", "Outro envio para este atendimento terminou ao mesmo "
                            + "tempo. Envie o arquivo de novo.");
                }
                view = arquivos.buscar(atendimentoId, id).map(ArquivoView::de).orElseThrow();
            }
            log.info("Atendimento {}: {} {} recebido ({} bytes, {})", atendimentoId, origem.codigo(), view.ordem(),
                    recebido.tamanho(), recebido.tipo().tipoDeMidia());
            publicador.publicar(atendimentoId);
            return view;
        } finally {
            pasta.descartar(recebido.arquivo());
        }
    }

    /** O arquivo de um anexo da conversa que não pôde ser baixado (o "plano B"): fica pronto, sem link. */
    public ArquivoView enviarConteudo(Usuario usuario, UUID atendimentoId, UUID arquivoId, String nome, InputStream corpo,
                                      long tamanhoInformado) {
        doDono(usuario, atendimentoId);
        ArquivoDoAtendimento anexo = arquivos.buscar(atendimentoId, arquivoId).orElseThrow(GerenciarArquivos::arquivoNaoEncontrado);
        if (!anexo.daConversa()) {
            throw new RegraNegocioException("SO_ANEXOS_DA_CONVERSA", "O envio à mão é para os anexos da conversa. "
                    + "Para trocar uma ligação, um vídeo ou um print, tire o arquivo e envie o novo.");
        }
        String nomeDoEnvio = nome == null || nome.isBlank() ? anexo.nome() : limparNome(nome);
        CategoriaDoArquivo pelaExtensao = categoriaAceita(nomeDoEnvio);
        Recebido recebido = receber(corpo, tamanhoInformado, pelaExtensao, nomeDoEnvio);
        try {
            tarefas.cancelarDoArquivo(arquivoId, clock.instant());
            String caminho = pasta.guardarArquivo(atendimentoId, recebido.arquivo(), "anexo-manual", anexo.ordem(), nomeDoEnvio);
            arquivos.guardarConteudo(arquivoId, recebido.conteudo(caminho), clock.instant(), null);
            if (anexo.caminho() != null && !anexo.caminho().equals(caminho)) {
                apagarSemFalhar(anexo.caminho());
            }
            pasta.descartar(pasta.parcialDoAnexo(atendimentoId, arquivoId));
            log.info("Atendimento {}: anexo {} enviado à mão ({} bytes, {})", atendimentoId, anexo.ordem(), recebido.tamanho(),
                    recebido.tipo().tipoDeMidia());
            processamento.atualizarSituacao(atendimentoId);
            publicador.publicar(atendimentoId);
            return arquivos.buscar(atendimentoId, arquivoId).map(ArquivoView::de).orElseThrow();
        } finally {
            pasta.descartar(recebido.arquivo());
        }
    }

    /**
     * Tira um arquivo: o enviado pela pessoa sai do atendimento; o anexo da conversa fica registrado (faz parte da
     * conversa), mas sem o arquivo.
     */
    public void tirar(Usuario usuario, UUID atendimentoId, UUID arquivoId) {
        doDono(usuario, atendimentoId);
        ArquivoDoAtendimento arquivo = arquivos.buscar(atendimentoId, arquivoId).orElseThrow(GerenciarArquivos::arquivoNaoEncontrado);
        tarefas.cancelarDoArquivo(arquivoId, clock.instant());
        if (arquivo.caminho() != null) {
            try {
                pasta.apagarArquivo(arquivo.caminho());
            } catch (UncheckedIOException e) {
                log.warn("Não foi possível apagar o arquivo {} do atendimento {}: {}", arquivo.ordem(), atendimentoId, e.getCause());
                throw new ConflitoException("ARQUIVO_EM_USO", "Não foi possível tirar o arquivo agora (ele pode estar aberto "
                        + "em outro programa). Feche-o e tente de novo.");
            }
        }
        if (arquivo.daConversa()) {
            arquivos.marcarRemovido(arquivoId);
            pasta.descartar(pasta.parcialDoAnexo(atendimentoId, arquivoId));
        } else {
            arquivos.apagar(arquivoId);
        }
        log.info("Atendimento {}: {} {} tirado", atendimentoId, arquivo.origem(), arquivo.ordem());
        processamento.atualizarSituacao(atendimentoId);
        publicador.publicar(atendimentoId);
    }

    // --------------------------------------------------------------------------------------------- apoio

    /** O que chegou: o arquivo temporário, o tipo conferido e o sha-256. */
    private record Recebido(Path arquivo, long tamanho, TipoDetectado tipo, String sha256) {

        ConteudoGuardado conteudo(String caminho) {
            return new ConteudoGuardado(tamanho, sha256, caminho, tipo.categoria(), tipo.tipoDeMidia(), tipo.suportado());
        }
    }

    private Recebido receber(InputStream corpo, long tamanhoInformado, CategoriaDoArquivo pelaExtensao, String nome) {
        long limite = limites.get(pelaExtensao);
        if (tamanhoInformado > limite) {
            throw grandeDemais(pelaExtensao, limite);
        }
        if (pasta.espacoLivre() - Math.max(0, tamanhoInformado) < espacoMinimo) {
            throw new ConflitoException("DISCO_CHEIO", "O disco do servidor está cheio. Libere espaço no computador do "
                    + "servidor (ou avise o administrador) e envie de novo.");
        }
        Path temporario = pasta.receber(corpo, limite, () -> grandeDemais(pelaExtensao, limite));
        try {
            long tamanho = java.nio.file.Files.size(temporario);
            if (tamanho == 0) {
                throw new RegraNegocioException("ARQUIVO_VAZIO", "O arquivo enviado está vazio.");
            }
            TipoDetectado tipo = TipoPeloConteudo.detectar(temporario, nome);
            if (!tipo.suportado()) {
                throw new RegraNegocioException("TIPO_NAO_SUPORTADO", "O conteúdo do arquivo não é de um tipo aceito ("
                        + (tipo.descricao() == null ? "tipo desconhecido" : tipo.descricao()) + "). Envie " + TIPOS_ACEITOS + ".");
            }
            Long limiteDoConteudo = limites.get(tipo.categoria());
            if (limiteDoConteudo != null && tamanho > limiteDoConteudo) {
                throw grandeDemais(tipo.categoria(), limiteDoConteudo);
            }
            return new Recebido(temporario, tamanho, tipo, Sha256.de(temporario));
        } catch (RuntimeException e) {
            pasta.descartar(temporario);
            throw e;
        } catch (java.io.IOException e) {
            pasta.descartar(temporario);
            throw new UncheckedIOException("Não foi possível ler o arquivo recebido", e);
        }
    }

    private static CategoriaDoArquivo categoriaAceita(String nome) {
        int ponto = nome.lastIndexOf('.');
        String extensao = ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        for (Map.Entry<CategoriaDoArquivo, Set<String>> aceitos : ACEITOS.entrySet()) {
            if (aceitos.getValue().contains(extensao)) {
                return aceitos.getKey();
            }
        }
        throw new RegraNegocioException("TIPO_NAO_SUPORTADO", "Este tipo de arquivo não é aceito"
                + (extensao.isEmpty() ? "" : " (." + extensao + ")") + ". Envie " + TIPOS_ACEITOS + ".");
    }

    private static RegraNegocioException grandeDemais(CategoriaDoArquivo categoria, long limite) {
        return new RegraNegocioException("ARQUIVO_GRANDE_DEMAIS", "O arquivo passa do tamanho máximo para "
                + nomeDa(categoria) + " (" + legivel(limite) + ").");
    }

    private static String nomeDa(CategoriaDoArquivo categoria) {
        return switch (categoria) {
            case IMAGEM -> "imagem";
            case AUDIO -> "áudio";
            case VIDEO -> "vídeo";
            default -> "documento";
        };
    }

    static String legivel(long bytes) {
        return bytes >= 1L << 30 ? (bytes >> 30) + " GB" : Math.max(1, bytes >> 20) + " MB";
    }

    /** Só o nome (sem pastas e sem caracteres de controle), até 200 caracteres. */
    private static String limparNome(String nome) {
        String limpo = nome == null ? "" : nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "").strip();
        if (limpo.isEmpty()) {
            throw new RegraNegocioException("NOME_OBRIGATORIO", "Informe o nome do arquivo.");
        }
        return limpo.length() > 200 ? limpo.substring(limpo.length() - 200) : limpo;
    }

    /** A data de modificação informada pelo navegador; uma data impossível (no futuro) é ignorada. */
    static Instant momento(String texto, Instant agora) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        Instant momento;
        try {
            momento = texto.strip().chars().allMatch(Character::isDigit)
                    ? Instant.ofEpochMilli(Long.parseLong(texto.strip()))
                    : Instant.parse(texto.strip());
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
        return momento.isAfter(agora.plus(Duration.ofDays(1))) || momento.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                ? null : momento;
    }

    private void apagarSemFalhar(String caminho) {
        try {
            pasta.apagarArquivo(caminho);
        } catch (UncheckedIOException e) {
            log.warn("Não foi possível apagar o arquivo antigo: {}", e.getCause());
        }
    }

    private void doDono(Usuario usuario, UUID atendimentoId) {
        atendimentos.estado(atendimentoId)
                .filter(e -> e.usuarioId().equals(usuario.id()))
                .orElseThrow(() -> new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO",
                        "Atendimento não encontrado. Ele pode ter sido apagado."));
    }

    private static RecursoNaoEncontradoException arquivoNaoEncontrado() {
        return new RecursoNaoEncontradoException("ARQUIVO_NAO_ENCONTRADO",
                "Arquivo não encontrado neste atendimento. Ele pode ter sido tirado.");
    }
}
