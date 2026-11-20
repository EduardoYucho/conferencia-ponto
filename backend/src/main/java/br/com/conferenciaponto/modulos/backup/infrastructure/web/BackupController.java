package br.com.conferenciaponto.modulos.backup.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.backup.application.acesso.PermissoesDoBackup;
import br.com.conferenciaponto.modulos.backup.domain.Cliente;
import br.com.conferenciaponto.modulos.backup.domain.Coleta;
import br.com.conferenciaponto.modulos.backup.domain.ErroDeColeta;
import br.com.conferenciaponto.modulos.backup.domain.Pedidos;
import br.com.conferenciaponto.modulos.backup.infrastructure.coleta.RepositorioDeClientes;
import br.com.conferenciaponto.modulos.backup.infrastructure.coleta.ServicoDeColetas;
import br.com.conferenciaponto.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;
import br.com.conferenciaponto.modulos.backup.infrastructure.tunel.ServicoDeTunel;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A tela "Backups de clientes" (menu Suporte). Todas as rotas exigem backup.coletar (InterceptorDoBackup); tirar do
 * histórico exige backup.apagar. O backup não passa por disco aqui: o navegador de quem gerou o link pede cada pedaço
 * ({@code receber}), grava na pasta escolhida no computador dele e confirma ({@code recebido}).
 *
 * <pre>
 * GET    /api/v1/backup/estado                    túnel e configuração
 * POST   /api/v1/backup/tunel/reiniciar           abre outro túnel (o endereço muda)
 * GET    /api/v1/backup/clientes                  clientes cadastrados
 * POST   /api/v1/backup/clientes                  {nome, pastaSistema}
 * PUT    /api/v1/backup/clientes/{nome}           {nome, pastaSistema}
 * DELETE /api/v1/backup/clientes/{nome}
 * GET    /api/v1/backup/coletas                   o histórico, a mais recente primeiro
 * POST   /api/v1/backup/coletas                   {cliente}: um link novo (quem gera é quem recebe)
 * GET    /api/v1/backup/coletas/{id}
 * POST   /api/v1/backup/coletas/{id}/cancelar
 * DELETE /api/v1/backup/coletas/{id}              tira do histórico (backup.apagar; o arquivo de quem recebeu fica)
 * -- só quem gerou o link --
 * GET    /api/v1/backup/coletas/{id}/receber      o próximo pedaço (espera até 25 s; 204 se não chegou), X-Seq e X-Sha256
 * POST   /api/v1/backup/coletas/{id}/recebido     {seq, sha256}: gravado na pasta
 * GET    /api/v1/backup/coletas/{id}/final        o último pedaço (manifesto e fim do .tar), depois de todos gravados
 * POST   /api/v1/backup/coletas/{id}/resultado    a conferência feita no navegador
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/backup")
public class BackupController {

    private static final Duration ESPERA_RECEBER = Duration.ofSeconds(25);
    /** Quando esta versão subiu: a tela compara e, se mudou (o sistema foi atualizado), pede para recarregar. */
    private static final String VERSAO = Instant.now().toString();

    private final ServicoDeColetas coletas;
    private final RepositorioDeClientes clientes;
    private final ServicoDeTunel tunel;
    private final ConfiguracaoDoBackup configuracao;
    private final AcessoUsuarios acesso;

    public BackupController(ServicoDeColetas coletas, RepositorioDeClientes clientes, ServicoDeTunel tunel,
                            ConfiguracaoDoBackup configuracao, AcessoUsuarios acesso) {
        this.coletas = coletas;
        this.clientes = clientes;
        this.tunel = tunel;
        this.configuracao = configuracao;
        this.acesso = acesso;
    }

    /**
     * Uma coleta na tela. {@code minha}: foi a pessoa logada que gerou o link (é o navegador dela que recebe).
     * {@code token} só enquanto chega (para montar o link).
     */
    public record Visao(String id, String token, String cliente, String nome, String situacao, String mensagem,
                        Instant criadaEm, String criadaPor, boolean minha, boolean receptorOnline, Instant expiraEm,
                        String link, String raiz, List<String> pastas, String computador, long totalArquivos,
                        long totalBytes, Coleta.Progresso progresso, long proximoSeq, long bytesRecebidos,
                        double velocidade, Long previsaoSegundos, Instant inicioEnvio, Instant ultimoContato,
                        Instant concluidaEm, Instant conferidaEm, long tamanhoArquivo, Coleta.Resumo resumo,
                        Coleta.Conferencia conferencia, List<String> alterados, List<String> naoLidos) {
    }

    public record PedidoCliente(String nome, String pastaSistema) {
    }

    public record PedidoColeta(String cliente) {
    }

    public record PedidoRecebido(long seq, String sha256) {
    }

    // ------------------------------------------------------------------ estado geral

    @GetMapping("/estado")
    public ApiResponse<Map<String, Object>> estado() {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("tunel", tunel.situacao());
        estado.put("nivelZstd", configuracao.nivelZstd());
        estado.put("validadeLinkHoras", configuracao.validadeLinkHoras());
        estado.put("podeApagar", acesso.logado().pode(PermissoesDoBackup.APAGAR));
        estado.put("versao", VERSAO);
        return ApiResponse.ok(estado);
    }

    @PostMapping("/tunel/reiniciar")
    public ApiResponse<ServicoDeTunel.Situacao> reiniciarTunel() {
        tunel.reiniciar();
        return ApiResponse.ok(tunel.situacao());
    }

    // ------------------------------------------------------------------ clientes

    @GetMapping("/clientes")
    public ApiResponse<List<Cliente>> listarClientes() {
        return ApiResponse.ok(clientes.listar());
    }

    @PostMapping("/clientes")
    public ApiResponse<Cliente> criarCliente(@RequestBody PedidoCliente pedido) {
        validarCliente(pedido);
        if (clientes.buscar(pedido.nome().trim()).isPresent()) {
            throw new ErroDeColeta(409, "CLIENTE_EXISTE", "Já existe um cliente com esse nome.");
        }
        return ApiResponse.ok(clientes.salvar(null, new Cliente(pedido.nome(), pedido.pastaSistema(), List.of())));
    }

    @PutMapping("/clientes/{nome}")
    public ApiResponse<Cliente> alterarCliente(@PathVariable String nome, @RequestBody PedidoCliente pedido) {
        validarCliente(pedido);
        Cliente atual = clientes.buscar(nome)
                .orElseThrow(() -> new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não encontrado."));
        if (!nome.equalsIgnoreCase(pedido.nome().trim()) && clientes.buscar(pedido.nome().trim()).isPresent()) {
            throw new ErroDeColeta(409, "CLIENTE_EXISTE", "Já existe um cliente com esse nome.");
        }
        return ApiResponse.ok(clientes.salvar(nome, new Cliente(pedido.nome(), pedido.pastaSistema(), atual.ultimaSelecao())));
    }

    @DeleteMapping("/clientes/{nome}")
    public ApiResponse<Void> removerCliente(@PathVariable String nome) {
        if (!clientes.remover(nome)) {
            throw new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não encontrado.");
        }
        return ApiResponse.ok(null);
    }

    private static void validarCliente(PedidoCliente pedido) {
        if (pedido.nome() == null || pedido.nome().isBlank()) {
            throw new ErroDeColeta(400, "NOME_OBRIGATORIO", "Informe o nome do cliente.");
        }
        if (pedido.nome().trim().length() > 80) {
            throw new ErroDeColeta(400, "NOME_LONGO", "Nome com no máximo 80 caracteres.");
        }
        if (pedido.pastaSistema() != null && pedido.pastaSistema().length() > 200) {
            throw new ErroDeColeta(400, "PASTA_LONGA", "Pasta do sistema com no máximo 200 caracteres.");
        }
    }

    // ------------------------------------------------------------------ coletas

    @GetMapping("/coletas")
    public ApiResponse<List<Visao>> listarColetas() {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(coletas.listar().stream().map(c -> visao(c, logado)).toList());
    }

    @GetMapping("/coletas/{id}")
    public ApiResponse<Visao> coleta(@PathVariable String id) {
        return ApiResponse.ok(visao(coletas.buscar(id), acesso.logado()));
    }

    @PostMapping("/coletas")
    public ApiResponse<Visao> criarColeta(@RequestBody PedidoColeta pedido) {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(visao(coletas.criar(pedido.cliente(), logado.id(), logado.login()), logado));
    }

    @PostMapping("/coletas/{id}/cancelar")
    public ApiResponse<Visao> cancelar(@PathVariable String id) {
        coletas.cancelar(id);
        return ApiResponse.ok(visao(coletas.buscar(id), acesso.logado()));
    }

    @DeleteMapping("/coletas/{id}")
    public ApiResponse<Void> apagar(@PathVariable String id) {
        if (!acesso.logado().pode(PermissoesDoBackup.APAGAR)) {
            throw new AcessoNegadoException("SEM_PERMISSAO", "Tirar backups do histórico é só para quem tem essa permissão.");
        }
        coletas.apagar(id);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------ navegador de quem recebe

    @GetMapping("/coletas/{id}/receber")
    public ResponseEntity<byte[]> receber(@PathVariable String id) {
        Optional<Pedidos.Entrega> entrega = coletas.proximaParte(id, acesso.logado().id(), ESPERA_RECEBER);
        if (entrega.isEmpty()) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Seq", Long.toString(entrega.get().seq()))
                .header("X-Sha256", entrega.get().sha256())
                .body(entrega.get().quadro());
    }

    @PostMapping("/coletas/{id}/recebido")
    public ApiResponse<Pedidos.ParteAceita> recebido(@PathVariable String id, @RequestBody PedidoRecebido pedido) {
        return ApiResponse.ok(coletas.confirmarParte(id, acesso.logado().id(), pedido.seq(), pedido.sha256()));
    }

    @GetMapping("/coletas/{id}/final")
    public ResponseEntity<byte[]> parteFinal(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(coletas.parteFinal(id, acesso.logado().id()));
    }

    @PostMapping("/coletas/{id}/resultado")
    public ApiResponse<Visao> resultado(@PathVariable String id, @RequestBody Pedidos.Resultado resultado) {
        Usuario logado = acesso.logado();
        return ApiResponse.ok(visao(coletas.registrarResultado(id, logado.id(), resultado), logado));
    }

    // ------------------------------------------------------------------ apoio

    private Visao visao(Coleta c, Usuario logado) {
        String endereco = tunel.situacao().endereco();
        boolean recebendo = c.situacao.recebendo();
        String link = recebendo && endereco != null ? endereco + "/c/" + c.token : null;
        double velocidade = recebendo ? coletas.velocidade(c.id) : 0;
        Long previsao = null;
        if (recebendo && c.progresso != null && c.progresso.lidoBytes() > 0 && c.totalBytes > 0 && velocidade > 0) {
            double estimativaTotal = (double) c.progresso.compactadoBytes() * c.totalBytes / c.progresso.lidoBytes();
            previsao = (long) Math.ceil(Math.max(0, estimativaTotal - c.bytesArquivo) / velocidade);
        }
        boolean minha = c.criadaPorId == null || c.criadaPorId.equals(logado.id());
        return new Visao(c.id, recebendo ? c.token : null, c.cliente, c.nome, c.situacao.name(), c.mensagem,
                c.criadaEm, c.criadaPor, minha, coletas.receptorOnline(c.id), c.expiraEm, link, c.raiz, c.pastas,
                c.computador, c.totalArquivos, c.totalBytes, c.progresso, c.proximoSeq,
                c.situacao.terminada() ? c.tamanhoArquivo : c.bytesArquivo, velocidade, previsao, c.inicioEnvio,
                c.ultimoContato, c.concluidaEm, c.conferidaEm, c.tamanhoArquivo, c.resumo, c.conferencia,
                c.alterados == null ? List.of() : c.alterados.stream().limit(100).toList(),
                c.naoLidos == null ? List.of() : c.naoLidos.stream().limit(100).toList());
    }
}
