package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.PermissoesDaBase;
import br.com.conferenciaponto.modulos.conhecimento.application.vetor.VetoresDaBase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.DadosDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.ImagemDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.MudancaDeSituacao;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.PastaDaBase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Um registro da base: abrir, editar, mudar a situação (com histórico), validar a solução e apagar. Quem pesquisa
 * vê qualquer registro; mudar é do autor ou de quem cura a base. Cada mudança leva a versão que a pessoa viu: se
 * outra pessoa mudou antes, nada muda e a pessoa recarrega.
 */
@Service
public class GerenciarRegistros {

    private static final Logger log = LoggerFactory.getLogger(GerenciarRegistros.class);
    static final int LIMITE_DA_VERSAO_CORRIGIDA = 40;

    private final Registros registros;
    private final PastaDaBase pasta;
    private final Clock clock;
    private final VetoresDaBase vetores;

    @Autowired
    public GerenciarRegistros(Registros registros, PastaDaBase pasta, Clock clock, VetoresDaBase vetores) {
        this.registros = registros;
        this.pasta = pasta;
        this.clock = clock;
        this.vetores = vetores;
    }

    /** Sem a pesquisa por significado. */
    public GerenciarRegistros(Registros registros, PastaDaBase pasta, Clock clock) {
        this(registros, pasta, clock, null);
    }

    /** Uma imagem do registro para a tela. */
    public record ArquivoDaImagem(Path local, String tipoDeMidia, String nome) {
    }

    public RegistroView detalhe(Usuario usuario, UUID id) {
        return view(usuario, existente(id));
    }

    /** O registro criado a partir de uma saída confirmada no gerador (para a tela do atendimento abrir o registro). */
    public UUID daSaida(UUID saidaId) {
        return registros.daSaida(saidaId).orElseThrow(GerenciarRegistros::naoEncontrado);
    }

    public RegistroView editar(Usuario usuario, UUID id, Integer versao, EdicaoDoRegistro edicao) {
        Registro registro = paraMudar(usuario, id, versao, "editar");
        if (edicao == null) {
            throw new RegraNegocioException("REGISTRO_INVALIDO", "Preencha o registro.");
        }
        DadosDoRegistro dados = DadosDoRegistro.conferir(edicao.titulo(), edicao.tipo(), edicao.cliente(),
                edicao.contato(), edicao.modulo(), edicao.telaNome(), edicao.telaCodigo(), edicao.sintoma(),
                edicao.mensagemErro(), edicao.causa(), edicao.resolucao());
        if (dados.equals(registro.dados())) {
            return view(usuario, registro);
        }
        conferirGravacao(id, registros.editar(id, registro.versao(), dados, clock.instant()));
        if (vetores != null) {
            vetores.textoMudou(id, dados.titulo(), dados.sintoma(), dados.mensagemErro(), dados.causa(), dados.resolucao());
        }
        log.info("Registro {} editado por {}", id, usuario.login());
        return detalhe(usuario, id);
    }

    /**
     * Muda a situação no desenvolvimento e grava quem mudou. O chamado vale para todas as situações; a versão da
     * correção, só para "corrigido". Repetir a situação com outro chamado ou outra versão também entra no histórico.
     */
    public RegistroView mudarSituacao(Usuario usuario, UUID id, Integer versao, String situacao, String chamadoDev,
                                      String versaoCorrecao) {
        Registro registro = paraMudar(usuario, id, versao, "mudar a situação");
        SituacaoDoRegistro para = SituacaoDoRegistro.doCodigo(situacao).orElseThrow(() -> new RegraNegocioException(
                "SITUACAO_INVALIDA", "Escolha a situação: resolvido no suporte, enviado ao desenvolvimento, corrigido ou "
                + "não será feito."));
        String chamado = RegistrarTextoConfirmado.chamado(chamadoDev);
        String corrigida = para == SituacaoDoRegistro.CORRIGIDO ? versaoCorrigida(versaoCorrecao) : null;
        if (para == registro.situacao() && Objects.equals(chamado, registro.chamadoDev())
                && Objects.equals(corrigida, registro.versaoCorrecao())) {
            return view(usuario, registro);
        }
        Instant agora = clock.instant();
        conferirGravacao(id, registros.mudarSituacao(id, registro.versao(),
                new MudancaDeSituacao(registro.situacao(), para, chamado, corrigida, usuario.login(), agora)));
        log.info("Registro {}: situação {} → {} (por {})", id, registro.situacao().codigo(), para.codigo(), usuario.login());
        return detalhe(usuario, id);
    }

    /** Marca a solução como validada (ou tira a marca). */
    public RegistroView validar(Usuario usuario, UUID id, Integer versao, boolean validada) {
        Registro registro = paraMudar(usuario, id, versao, "validar a solução");
        if (registro.solucaoValidada() == validada) {
            return view(usuario, registro);
        }
        conferirGravacao(id, registros.validar(id, registro.versao(), validada, usuario.login(), clock.instant()));
        log.info("Registro {}: solução {} por {}", id, validada ? "validada" : "sem validação", usuario.login());
        return detalhe(usuario, id);
    }

    /** Apaga o registro e as imagens dele. */
    public void apagar(Usuario usuario, UUID id) {
        Registro registro = existente(id);
        exigirAutorOuCurador(usuario, registro, "apagar");
        if (!registros.apagar(id)) {
            throw naoEncontrado();
        }
        pasta.apagar(id);
        if (vetores != null) {
            vetores.esquecer(id);
        }
        log.info("Registro {} apagado por {}", id, usuario.login());
    }

    public ArquivoDaImagem imagem(Usuario usuario, UUID id, int numero) {
        existente(id);
        ImagemDoRegistro imagem = registros.imagens(id).stream().filter(i -> i.numero() == numero).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("IMAGEM_NAO_ENCONTRADA", "Imagem não encontrada."));
        Path local = pasta.local(imagem.caminho());
        if (!Files.isRegularFile(local)) {
            throw new RecursoNaoEncontradoException("IMAGEM_SEM_ARQUIVO", "O arquivo da imagem " + numero + " não está na "
                    + "pasta da base. Se a pasta foi restaurada de um backup, confira se a pasta base foi junto.");
        }
        return new ArquivoDaImagem(local, tipoDeMidia(imagem.caminho()), nome(imagem.caminho()));
    }

    // --------------------------------------------------------------------------------------------- apoio

    private Registro paraMudar(Usuario usuario, UUID id, Integer versao, String acao) {
        Registro registro = existente(id);
        exigirAutorOuCurador(usuario, registro, acao);
        if (versao == null) {
            throw new RegraNegocioException("VERSAO_AUSENTE", "Recarregue o registro e tente de novo.");
        }
        if (versao != registro.versao()) {
            throw jaAlterado();
        }
        return registro;
    }

    private void conferirGravacao(UUID id, boolean gravou) {
        if (!gravou) {
            existente(id);
            throw jaAlterado();
        }
    }

    private void exigirAutorOuCurador(Usuario usuario, Registro registro, String acao) {
        if (!podeMudar(usuario, registro)) {
            throw new AcessoNegadoException("SEM_PERMISSAO_NA_BASE", "Só o autor do registro (" + registro.autorNome()
                    + ") ou quem cura a base pode " + acao + ".");
        }
    }

    private boolean podeMudar(Usuario usuario, Registro registro) {
        return registro.autorId().equals(usuario.id()) || usuario.ativo() && usuario.pode(PermissoesDaBase.CURAR);
    }

    private Registro existente(UUID id) {
        return registros.buscar(id).orElseThrow(GerenciarRegistros::naoEncontrado);
    }

    private RegistroView view(Usuario usuario, Registro r) {
        List<RegistroView.Imagem> imagens = registros.imagens(r.id()).stream()
                .map(i -> new RegistroView.Imagem(i.numero(), i.legenda(), Files.isRegularFile(pasta.local(i.caminho())),
                        nome(i.caminho())))
                .toList();
        List<RegistroView.Mudanca> historico = registros.historico(r.id()).stream()
                .map(m -> new RegistroView.Mudanca(m.de() == null ? null : m.de().codigo(),
                        m.de() == null ? null : m.de().rotulo(), m.para().codigo(), m.para().rotulo(), m.chamadoDev(),
                        m.versaoCorrecao(), m.por(), m.em()))
                .toList();
        var d = r.dados();
        return new RegistroView(r.id(), r.origem(), d.tipo().codigo(), d.tipo().rotulo(), d.titulo(), d.cliente(),
                d.contato(), d.modulo(), d.telaNome(), d.telaCodigo(), d.sintoma(), d.mensagemErro(), d.causa(),
                d.resolucao(), r.resolucaoHtml(), r.situacao().codigo(), r.situacao().rotulo(), r.chamadoDev(),
                r.versaoCorrecao(), r.solucaoValidada(), r.validadaPor(), r.validadaEm(), r.versao(), r.criadoEm(),
                r.atualizadoEm(), new RegistroView.Autor(r.autorId(), r.autorNome(), r.autorLogin()), r.atendimentoId(),
                r.saidaId(), imagens, historico, podeMudar(usuario, r), r.autorId().equals(usuario.id()),
                vetores != null && vetores.tem(r.id()));
    }

    private static String versaoCorrigida(String versao) {
        if (versao == null || versao.isBlank()) {
            return null;
        }
        String limpa = versao.replaceAll("\\s+", " ").strip();
        if (limpa.length() > LIMITE_DA_VERSAO_CORRIGIDA) {
            throw new RegraNegocioException("VERSAO_LONGA", "A versão da correção tem " + limpa.length()
                    + " caracteres (até " + LIMITE_DA_VERSAO_CORRIGIDA + ").");
        }
        return limpa;
    }

    private static String nome(String caminho) {
        return Path.of(caminho).getFileName().toString();
    }

    static String tipoDeMidia(String caminho) {
        String nome = nome(caminho).toLowerCase(Locale.ROOT);
        String ext = nome.contains(".") ? nome.substring(nome.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> "application/octet-stream";
        };
    }

    static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("REGISTRO_NAO_ENCONTRADO", "Registro não encontrado. Ele pode ter sido "
                + "apagado.");
    }

    private static ConflitoException jaAlterado() {
        return new ConflitoException("REGISTRO_JA_ALTERADO", "Este registro foi alterado por outra pessoa; recarregue para "
                + "ver a versão atual antes de mudar.");
    }
}
