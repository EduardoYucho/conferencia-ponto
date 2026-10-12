package br.com.conferenciaponto.modulos.conhecimento.infrastructure.web;

import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.CasosParecidos;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.FiltrosDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.PesquisarNaBase;
import br.com.conferenciaponto.modulos.conhecimento.application.pesquisa.ResultadoDaPesquisa;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.EdicaoDoRegistro;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.GerenciarRegistros;
import br.com.conferenciaponto.modulos.conhecimento.application.registro.RegistroView;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Os registros da base de conhecimento (todas as rotas exigem poder pesquisar; mudar é do autor ou de quem cura).
 *
 * <pre>
 * GET    /api/v1/conhecimento/registros?q=&amp;tipo=&amp;situacao=&amp;cliente=&amp;modulo=&amp;autor=&amp;de=&amp;ate=&amp;validadas=&amp;pagina=
 * GET    /api/v1/conhecimento/autores                           quem já escreveu registros (filtro)
 * GET    /api/v1/conhecimento/registros/da-saida/{saidaId}      {id} do registro de um texto confirmado
 * POST   /api/v1/conhecimento/registros/parecidos               {titulo, texto, telaCodigo, atendimentoId}: até 5 parecidos
 * GET    /api/v1/conhecimento/registros/{id}                    o registro, as imagens e o histórico
 * PUT    /api/v1/conhecimento/registros/{id}                    {versao, campos...}
 * PUT    /api/v1/conhecimento/registros/{id}/situacao           {versao, situacao, chamadoDev, versaoCorrecao}
 * POST   /api/v1/conhecimento/registros/{id}/validar-solucao    {versao, validada}
 * DELETE /api/v1/conhecimento/registros/{id}
 * GET    /api/v1/conhecimento/registros/{id}/imagens/{n}        a imagem N
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/conhecimento")
public class RegistrosController {

    private final PesquisarNaBase pesquisa;
    private final GerenciarRegistros registros;
    private final CasosParecidos parecidos;
    private final AcessoUsuarios acesso;

    public RegistrosController(PesquisarNaBase pesquisa, GerenciarRegistros registros, CasosParecidos parecidos,
                               AcessoUsuarios acesso) {
        this.pesquisa = pesquisa;
        this.registros = registros;
        this.parecidos = parecidos;
        this.acesso = acesso;
    }

    /** O formulário do registro, com a versão que a pessoa viu. */
    public record PedidoDeEdicao(Integer versao, String titulo, String tipo, String cliente, String contato, String modulo,
                                 String telaNome, String telaCodigo, String sintoma, String mensagemErro, String causa,
                                 String resolucao) {
    }

    public record PedidoDeSituacao(Integer versao, String situacao, String chamadoDev, String versaoCorrecao) {
    }

    public record PedidoDeValidacao(Integer versao, Boolean validada) {
    }

    @GetMapping("/registros")
    public ApiResponse<ResultadoDaPesquisa> pesquisar(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String situacao,
            @RequestParam(required = false) String cliente,
            @RequestParam(required = false) String modulo,
            @RequestParam(required = false) UUID autor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestParam(defaultValue = "false") boolean validadas,
            @RequestParam(required = false) Integer pagina) {
        return ApiResponse.ok(pesquisa.pesquisar(acesso.logado(),
                new FiltrosDaPesquisa(q, tipo, situacao, cliente, modulo, autor, de, ate, validadas, pagina)));
    }

    @GetMapping("/autores")
    public ApiResponse<List<PesquisarNaBase.AutorView>> autores() {
        return ApiResponse.ok(pesquisa.autores());
    }

    @GetMapping("/registros/da-saida/{saidaId}")
    public ApiResponse<Map<String, UUID>> daSaida(@PathVariable UUID saidaId) {
        return ApiResponse.ok(Map.of("id", registros.daSaida(saidaId)));
    }

    @PostMapping("/registros/parecidos")
    public ApiResponse<CasosParecidos.Resultado> parecidos(@RequestBody(required = false) CasosParecidos.Pedido pedido) {
        return ApiResponse.ok(parecidos.procurar(acesso.logado(), pedido));
    }

    @GetMapping("/registros/{id}")
    public ApiResponse<RegistroView> detalhe(@PathVariable UUID id) {
        return ApiResponse.ok(registros.detalhe(acesso.logado(), id));
    }

    @PutMapping("/registros/{id}")
    public ApiResponse<RegistroView> editar(@PathVariable UUID id, @RequestBody PedidoDeEdicao pedido) {
        EdicaoDoRegistro edicao = pedido == null ? null : new EdicaoDoRegistro(pedido.titulo(), pedido.tipo(),
                pedido.cliente(), pedido.contato(), pedido.modulo(), pedido.telaNome(), pedido.telaCodigo(), pedido.sintoma(),
                pedido.mensagemErro(), pedido.causa(), pedido.resolucao());
        return ApiResponse.ok(registros.editar(acesso.logado(), id, pedido == null ? null : pedido.versao(), edicao));
    }

    @PutMapping("/registros/{id}/situacao")
    public ApiResponse<RegistroView> situacao(@PathVariable UUID id, @RequestBody PedidoDeSituacao pedido) {
        PedidoDeSituacao p = pedido == null ? new PedidoDeSituacao(null, null, null, null) : pedido;
        return ApiResponse.ok(registros.mudarSituacao(acesso.logado(), id, p.versao(), p.situacao(), p.chamadoDev(),
                p.versaoCorrecao()));
    }

    @PostMapping("/registros/{id}/validar-solucao")
    public ApiResponse<RegistroView> validar(@PathVariable UUID id, @RequestBody PedidoDeValidacao pedido) {
        PedidoDeValidacao p = pedido == null ? new PedidoDeValidacao(null, null) : pedido;
        return ApiResponse.ok(registros.validar(acesso.logado(), id, p.versao(), p.validada() == null || p.validada()));
    }

    @DeleteMapping("/registros/{id}")
    public ApiResponse<Void> apagar(@PathVariable UUID id) {
        registros.apagar(acesso.logado(), id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/registros/{id}/imagens/{numero}")
    public ResponseEntity<Resource> imagem(@PathVariable UUID id, @PathVariable int numero) {
        GerenciarRegistros.ArquivoDaImagem imagem = registros.imagem(acesso.logado(), id, numero);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imagem.tipoDeMidia()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(imagem.nome()).build().toString())
                .body(new FileSystemResource(imagem.local()));
    }
}
