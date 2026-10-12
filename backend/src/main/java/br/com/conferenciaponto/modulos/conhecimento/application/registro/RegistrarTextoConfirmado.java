package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.DadosDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.ImagemDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.NovoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.PastaDaBase;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.Registros;
import br.com.conferenciaponto.modulos.conhecimento.domain.registro.SituacaoDoRegistro;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transforma o texto confirmado no gerador em registro da base: confere o formulário, copia as imagens para a pasta da
 * base (elas não dependem mais dos arquivos do atendimento, que a retenção apaga) e grava o registro com a primeira
 * linha do histórico. Roda dentro da transação da confirmação: se algo falhar, a saída continua pronta e as cópias
 * são apagadas.
 */
@Component
public class RegistrarTextoConfirmado {

    private static final Logger log = LoggerFactory.getLogger(RegistrarTextoConfirmado.class);
    static final int LIMITE_DO_CHAMADO = 60;
    private static final Safelist PERMITIDAS = Safelist.relaxed().removeTags("img");

    private final Registros registros;
    private final PastaDaBase pasta;
    private final GerenciarAcessosABase acessos;
    private final UsuarioRepository usuarios;

    public RegistrarTextoConfirmado(Registros registros, PastaDaBase pasta, GerenciarAcessosABase acessos,
                                    UsuarioRepository usuarios) {
        this.registros = registros;
        this.pasta = pasta;
        this.acessos = acessos;
        this.usuarios = usuarios;
    }

    @EventListener
    public void aoConfirmar(TextoConfirmado texto) {
        Usuario autor = usuarios.buscarPorId(texto.autorId()).orElseThrow(() -> semPermissao());
        if (!acessos.acessoDe(autor).pesquisar()) {
            throw semPermissao();
        }
        TextoConfirmado.Formulario f = texto.formulario();
        DadosDoRegistro dados = DadosDoRegistro.conferir(f.titulo(), f.tipo(), f.cliente(), f.contato(), f.modulo(),
                f.telaNome(), f.telaCodigo(), f.sintoma(), f.mensagemErro(), f.causa(), f.resolucao());
        SituacaoDoRegistro situacao = SituacaoDoRegistro.doCodigo(f.situacao()).orElseThrow(() ->
                new RegraNegocioException("REGISTRO_INVALIDO", "Confira o registro: escolha a situação."));
        String chamado = chamado(f.chamadoDev());
        UUID id = UUID.randomUUID();
        apagarSeDesfizer(id);
        List<ImagemDoRegistro> imagens = new ArrayList<>();
        try {
            for (TextoConfirmado.Imagem imagem : texto.imagens()) {
                imagens.add(new ImagemDoRegistro(imagem.numero(), copiar(id, imagem), imagem.legenda()));
            }
            registros.criar(new NovoRegistro(id, texto.atendimentoId(), texto.saidaId(), autor.id(), autor.login(), dados,
                    limpar(texto.html()), situacao, chamado, texto.confirmadoEm()), imagens);
        } catch (RuntimeException e) {
            pasta.apagar(id);
            throw e;
        }
        log.info("Registro {} criado na base a partir da saída {} ({}, {} imagens, por {})", id, texto.saidaId(),
                dados.tipo().codigo(), imagens.size(), autor.login());
    }

    private String copiar(UUID id, TextoConfirmado.Imagem imagem) {
        try {
            return pasta.copiar(id, imagem.numero(), imagem.arquivo());
        } catch (UncheckedIOException e) {
            log.warn("Não foi possível copiar a imagem {} para o registro {}: {}", imagem.numero(), id, e.toString());
            throw new RegraNegocioException("IMAGEM_NAO_COPIADA", "Não foi possível guardar a imagem " + imagem.numero()
                    + " na base (o disco pode estar cheio). Nada foi enviado; tente de novo.");
        }
    }

    /** Se a transação da confirmação for desfeita depois daqui, as imagens copiadas saem também. */
    private void apagarSeDesfizer(UUID id) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        pasta.apagar(id);
                    }
                }
            });
        }
    }

    static String chamado(String chamado) {
        if (chamado == null || chamado.isBlank()) {
            return null;
        }
        String limpo = chamado.replaceAll("\\s+", " ").strip();
        if (limpo.length() > LIMITE_DO_CHAMADO) {
            throw new RegraNegocioException("REGISTRO_INVALIDO", "Confira o registro: o chamado no desenvolvimento tem "
                    + limpo.length() + " caracteres (até " + LIMITE_DO_CHAMADO + ").");
        }
        return limpo;
    }

    /** O texto vem montado pelo gerador; aqui passa de novo pela lista de tags permitidas, porque a tela o mostra. */
    static String limpar(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        return Jsoup.clean(html, "", PERMITIDAS, new Document.OutputSettings().prettyPrint(false));
    }

    private static AcessoNegadoException semPermissao() {
        return new AcessoNegadoException("SEM_PERMISSAO_NA_BASE", "A base de conhecimento não está liberada para você. "
                + "Peça ao administrador o acesso à base para enviar textos para ela.");
    }
}
