package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProcessarAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.CopiaView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.GerenciarSaidas;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.LinhaDoTempoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.ModoDeCopia;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.SaidaView;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

/**
 * Os textos gerados de um atendimento (resumo e, nas etapas seguintes, os chamados para o desenvolvimento).
 *
 * <pre>
 * GET  /api/v1/atendimentos/{id}/saidas                               a versão mais recente de cada tipo
 * POST /api/v1/atendimentos/{id}/saidas/{tipo}/gerar                  gerar de novo (aproveita as análises)
 * GET  /api/v1/atendimentos/{id}/linha-do-tempo                       o material da redação (fontes de cada trecho)
 * GET  /api/v1/atendimentos/{id}/saidas/{saidaId}                     o texto, as imagens e os avisos
 * PUT  /api/v1/atendimentos/{id}/saidas/{saidaId}/assunto             {assunto} até 150 caracteres
 * PUT  /api/v1/atendimentos/{id}/saidas/{saidaId}/tela                {telaFuncionalidade} do cabeçalho dos chamados
 * PUT  /api/v1/atendimentos/{id}/saidas/{saidaId}/texto               {html} editado (limpo no servidor)
 * PUT  /api/v1/atendimentos/{id}/saidas/{saidaId}/imagens             {incluidas: [ids na ordem]} (o servidor renumera)
 * POST /api/v1/atendimentos/{id}/saidas/{saidaId}/desfazer            volta ao texto gerado
 * GET  /api/v1/atendimentos/{id}/saidas/{saidaId}/copia?modo=         com_imagens, so_texto ou codigo_fonte
 * GET  /api/v1/atendimentos/{id}/saidas/{saidaId}/imagens/{imagemId}  a imagem (miniatura na tela)
 * GET  /api/v1/atendimentos/{id}/saidas/{saidaId}/imagens.zip         Imagem_01..., na ordem do texto
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/atendimentos/{id}")
public class SaidasController {

    /** O .zip é montado na memória: acima disto, a pessoa baixa as imagens uma a uma. */
    private static final long LIMITE_DO_ZIP = 200L * 1024 * 1024;

    private final GerenciarSaidas saidas;
    private final ProcessarAtendimentos processamento;
    private final AcessoUsuarios acesso;

    public SaidasController(GerenciarSaidas saidas, ProcessarAtendimentos processamento, AcessoUsuarios acesso) {
        this.saidas = saidas;
        this.processamento = processamento;
        this.acesso = acesso;
    }

    /** O texto editado. */
    public record PedidoDeTexto(String html) {
    }

    /** O assunto editado. */
    public record PedidoDeAssunto(String assunto) {
    }

    /** A Tela/Funcionalidade do cabeçalho dos chamados. */
    public record PedidoDeTela(String telaFuncionalidade) {
    }

    /** As imagens que ficam no texto, na ordem. */
    public record PedidoDeImagens(List<UUID> incluidas) {
    }

    @GetMapping("/saidas")
    public ApiResponse<List<EstadoDaSaida>> listar(@PathVariable UUID id) {
        return ApiResponse.ok(saidas.listar(acesso.logado(), id));
    }

    @PostMapping("/saidas/{tipo}/gerar")
    public ApiResponse<ProgressoView> gerar(@PathVariable UUID id, @PathVariable String tipo) {
        return ApiResponse.ok(processamento.gerar(acesso.logado(), id, tipo(tipo)));
    }

    @GetMapping("/linha-do-tempo")
    public ApiResponse<LinhaDoTempoView> linhaDoTempo(@PathVariable UUID id) {
        return ApiResponse.ok(saidas.linhaDoTempo(acesso.logado(), id));
    }

    @GetMapping("/saidas/{saidaId}")
    public ApiResponse<SaidaView> detalhe(@PathVariable UUID id, @PathVariable UUID saidaId) {
        return ApiResponse.ok(saidas.detalhe(acesso.logado(), id, saidaId));
    }

    @PutMapping("/saidas/{saidaId}/assunto")
    public ApiResponse<SaidaView> assunto(@PathVariable UUID id, @PathVariable UUID saidaId,
                                          @RequestBody PedidoDeAssunto pedido) {
        return ApiResponse.ok(saidas.salvarAssunto(acesso.logado(), id, saidaId, pedido == null ? null : pedido.assunto()));
    }

    @PutMapping("/saidas/{saidaId}/tela")
    public ApiResponse<SaidaView> tela(@PathVariable UUID id, @PathVariable UUID saidaId, @RequestBody PedidoDeTela pedido) {
        return ApiResponse.ok(saidas.salvarTela(acesso.logado(), id, saidaId, pedido == null ? null : pedido.telaFuncionalidade()));
    }

    @PutMapping("/saidas/{saidaId}/texto")
    public ApiResponse<SaidaView> texto(@PathVariable UUID id, @PathVariable UUID saidaId, @RequestBody PedidoDeTexto pedido) {
        return ApiResponse.ok(saidas.salvarTexto(acesso.logado(), id, saidaId, pedido == null ? null : pedido.html()));
    }

    @PutMapping("/saidas/{saidaId}/imagens")
    public ApiResponse<SaidaView> imagens(@PathVariable UUID id, @PathVariable UUID saidaId,
                                          @RequestBody PedidoDeImagens pedido) {
        return ApiResponse.ok(saidas.ordenarImagens(acesso.logado(), id, saidaId, pedido == null ? null : pedido.incluidas()));
    }

    @PostMapping("/saidas/{saidaId}/desfazer")
    public ApiResponse<SaidaView> desfazer(@PathVariable UUID id, @PathVariable UUID saidaId) {
        return ApiResponse.ok(saidas.desfazerEdicao(acesso.logado(), id, saidaId));
    }

    @GetMapping("/saidas/{saidaId}/copia")
    public ApiResponse<CopiaView> copia(@PathVariable UUID id, @PathVariable UUID saidaId, @RequestParam String modo) {
        ModoDeCopia qual = ModoDeCopia.doCodigo(modo).orElseThrow(() -> new RegraNegocioException("MODO_INVALIDO",
                "Modo de cópia desconhecido: use com_imagens, so_texto ou codigo_fonte."));
        return ApiResponse.ok(saidas.copia(acesso.logado(), id, saidaId, qual));
    }

    @GetMapping("/saidas/{saidaId}/imagens/{imagemId}")
    public ResponseEntity<Resource> imagem(@PathVariable UUID id, @PathVariable UUID saidaId, @PathVariable UUID imagemId) {
        GerenciarSaidas.ArquivoDaImagem imagem = saidas.imagem(acesso.logado(), id, saidaId, imagemId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(imagem.tipoDeMidia()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(imagem.nome()).build().toString())
                .body(new FileSystemResource(imagem.local()));
    }

    @GetMapping("/saidas/{saidaId}/imagens.zip")
    public ResponseEntity<Resource> zip(@PathVariable UUID id, @PathVariable UUID saidaId) {
        List<GerenciarSaidas.ArquivoDaImagem> imagens = saidas.imagensParaOZip(acesso.logado(), id, saidaId);
        long total = imagens.stream().mapToLong(i -> i.local().toFile().length()).sum();
        if (total > LIMITE_DO_ZIP) {
            throw new RegraNegocioException("ZIP_GRANDE_DEMAIS", "As imagens somam mais de 200 MB. Baixe uma a uma.");
        }
        ByteArrayOutputStream conteudo = new ByteArrayOutputStream((int) Math.min(total + 4096, Integer.MAX_VALUE));
        GerenciarSaidas.escreverZip(imagens, conteudo);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("imagens.zip").build().toString())
                .body(new ByteArrayResource(conteudo.toByteArray()));
    }

    static TipoDeSaida tipo(String codigo) {
        return TipoDeSaida.doCodigo(codigo).orElseThrow(() -> new RegraNegocioException("SAIDA_DESCONHECIDA",
                "Tipo de texto desconhecido: " + (codigo == null ? "" : codigo.replaceAll("[^a-z_]", "")) + "."));
    }
}
