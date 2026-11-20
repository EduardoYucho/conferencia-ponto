package br.com.receptorbackup.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.receptorbackup.cliente.Cliente;
import br.com.receptorbackup.cliente.RepositorioDeClientes;
import br.com.receptorbackup.coleta.Coleta;
import br.com.receptorbackup.coleta.ErroDeColeta;
import br.com.receptorbackup.coleta.ServicoDeColetas;
import br.com.receptorbackup.config.Propriedades;
import br.com.receptorbackup.tunel.ServicoDeTunel;

/** API da tela do Receptor (só nesta máquina; veja o FiltroDePortas). */
@RestController
@RequestMapping("/api")
public class AdminController {

    private final ServicoDeColetas coletas;
    private final RepositorioDeClientes clientes;
    private final ServicoDeTunel tunel;
    private final Propriedades propriedades;

    public AdminController(ServicoDeColetas coletas, RepositorioDeClientes clientes, ServicoDeTunel tunel,
            Propriedades propriedades) {
        this.coletas = coletas;
        this.clientes = clientes;
        this.tunel = tunel;
        this.propriedades = propriedades;
    }

    public record Visao(String id, String cliente, String nome, String situacao, String mensagem, Instant criadaEm,
            Instant expiraEm, String link, String linkLocal, String raiz, List<String> pastas, String computador,
            String navegador, long totalArquivos, long totalBytes, Coleta.Progresso progresso, long bytesRecebidos,
            long brutoRecebido, long arquivosRecebidos, double velocidade, Long previsaoSegundos,
            Instant inicioEnvio, Instant ultimoContato, Instant concluidaEm, long tamanhoArquivo, String arquivo,
            Coleta.Resumo resumo, Coleta.Conferencia conferencia, Coleta.Extracao extracao, Long andamentoTrabalho,
            List<String> alterados, List<String> naoLidos) {
    }

    public record PedidoCliente(String nome, String pastaSistema) {
    }

    public record PedidoColeta(String cliente) {
    }

    public record PedidoExtracao(String destino) {
    }

    // ------------------------------------------------------------------ estado geral

    @GetMapping("/estado")
    public Map<String, Object> estado() throws IOException {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("tunel", tunel.situacao());
        estado.put("pastaDados", propriedades.pastaDados().toAbsolutePath().toString());
        Files.createDirectories(propriedades.pastaDados());
        estado.put("espacoLivre", Files.getFileStore(propriedades.pastaDados()).getUsableSpace());
        estado.put("nivelZstd", propriedades.nivelZstd());
        estado.put("limpezaDias", propriedades.limpezaDias());
        estado.put("manterUltimaPorCliente", propriedades.manterUltimaPorCliente());
        estado.put("validadeLinkHoras", propriedades.validadeLinkHoras());
        return estado;
    }

    @GetMapping("/tunel/log")
    public List<String> logDoTunel() {
        return tunel.ultimasLinhas();
    }

    @PostMapping("/tunel/reiniciar")
    public ResponseEntity<Void> reiniciarTunel() {
        tunel.reiniciar();
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ clientes

    @GetMapping("/clientes")
    public List<Cliente> listarClientes() {
        return clientes.listar();
    }

    @PostMapping("/clientes")
    public Cliente criarCliente(@RequestBody PedidoCliente pedido) {
        validarCliente(pedido);
        if (clientes.buscar(pedido.nome().trim()).isPresent()) {
            throw new ErroDeColeta(409, "CLIENTE_EXISTE", "Já existe um cliente com esse nome.");
        }
        return clientes.salvar(null, new Cliente(pedido.nome(), pedido.pastaSistema(), List.of()));
    }

    @PutMapping("/clientes/{nome}")
    public Cliente alterarCliente(@PathVariable String nome, @RequestBody PedidoCliente pedido) {
        validarCliente(pedido);
        Cliente atual = clientes.buscar(nome)
                .orElseThrow(() -> new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não encontrado."));
        if (!nome.equalsIgnoreCase(pedido.nome().trim()) && clientes.buscar(pedido.nome().trim()).isPresent()) {
            throw new ErroDeColeta(409, "CLIENTE_EXISTE", "Já existe um cliente com esse nome.");
        }
        return clientes.salvar(nome, new Cliente(pedido.nome(), pedido.pastaSistema(), atual.ultimaSelecao()));
    }

    @DeleteMapping("/clientes/{nome}")
    public ResponseEntity<Void> removerCliente(@PathVariable String nome) {
        if (!clientes.remover(nome)) {
            throw new ErroDeColeta(404, "CLIENTE_DESCONHECIDO", "Cliente não encontrado.");
        }
        return ResponseEntity.noContent().build();
    }

    private static void validarCliente(PedidoCliente pedido) {
        if (pedido.nome() == null || pedido.nome().isBlank()) {
            throw new ErroDeColeta(400, "NOME_OBRIGATORIO", "Informe o nome do cliente.");
        }
        if (pedido.nome().trim().length() > 80) {
            throw new ErroDeColeta(400, "NOME_LONGO", "Nome com no máximo 80 caracteres.");
        }
    }

    // ------------------------------------------------------------------ coletas

    @GetMapping("/coletas")
    public List<Visao> listarColetas() {
        return coletas.listar().stream().map(this::visao).toList();
    }

    @GetMapping("/coletas/{id}")
    public Visao coleta(@PathVariable String id) {
        return visao(coletas.buscar(id));
    }

    @PostMapping("/coletas")
    public Visao criarColeta(@RequestBody PedidoColeta pedido) {
        return visao(coletas.criar(pedido.cliente()));
    }

    @PostMapping("/coletas/{id}/cancelar")
    public Visao cancelar(@PathVariable String id) {
        coletas.cancelar(id);
        return visao(coletas.buscar(id));
    }

    @PostMapping("/coletas/{id}/conferir")
    public Visao conferir(@PathVariable String id) {
        coletas.conferirDeNovo(id);
        return visao(coletas.buscar(id));
    }

    @PostMapping("/coletas/{id}/extrair")
    public Visao extrair(@PathVariable String id, @RequestBody(required = false) PedidoExtracao pedido) {
        coletas.extrair(id, pedido == null ? null : pedido.destino());
        return visao(coletas.buscar(id));
    }

    @PostMapping("/coletas/{id}/mostrar")
    public ResponseEntity<Void> mostrarNaPasta(@PathVariable String id) throws IOException {
        Coleta coleta = coletas.buscar(id);
        Path alvo = coletas.arquivoFinal(coleta);
        if (coleta.extracao != null && "EXTRAIDO".equals(coleta.extracao.situacao())) {
            alvo = Path.of(coleta.extracao.destino());
        }
        if (!Files.exists(alvo)) {
            alvo = coletas.pastaDoCliente(coleta);
            Files.createDirectories(alvo);
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            new ProcessBuilder("explorer.exe", "/select," + alvo.toAbsolutePath()).start();
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/coletas/{id}")
    public ResponseEntity<Void> apagar(@PathVariable String id) {
        coletas.apagar(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/limpeza")
    public Map<String, Integer> limpar() {
        int dias = propriedades.limpezaDias();
        if (dias <= 0) {
            throw new ErroDeColeta(409, "LIMPEZA_DESLIGADA",
                    "A limpeza automática está desligada (receptor.limpeza-dias = 0 na configuração).");
        }
        return Map.of("apagadas", coletas.limpar(dias, propriedades.manterUltimaPorCliente()));
    }

    // ------------------------------------------------------------------ espaço

    @GetMapping("/espaco")
    public Map<String, Object> espaco() throws IOException {
        Path base = propriedades.pastaDados();
        Files.createDirectories(base);
        List<Map<String, Object>> porCliente = new ArrayList<>();
        long total = 0;
        try (Stream<Path> pastas = Files.list(base)) {
            for (Path pasta : pastas.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted().toList()) {
                long bytes = 0;
                int quantidade = 0;
                try (Stream<Path> arquivos = Files.list(pasta)) {
                    for (Path arquivo : arquivos.filter(a -> a.getFileName().toString().endsWith(".tar.zst")).toList()) {
                        bytes += Files.size(arquivo);
                        quantidade++;
                    }
                }
                total += bytes;
                porCliente.add(Map.of("pasta", pasta.getFileName().toString(), "bytes", bytes, "backups", quantidade));
            }
        }
        return Map.of("total", total, "livre", Files.getFileStore(base).getUsableSpace(), "porCliente", porCliente);
    }

    // ------------------------------------------------------------------ apoio

    private Visao visao(Coleta c) {
        String endereco = tunel.situacao().endereco();
        String link = endereco == null ? null : endereco + "/c/" + c.token;
        String linkLocal = "http://127.0.0.1:" + propriedades.portaPublica() + "/c/" + c.token;
        boolean recebendo = c.situacao.recebendo();
        double velocidade = recebendo ? coletas.velocidade(c.id) : 0;
        Long previsao = null;
        if (recebendo && c.progresso != null && c.progresso.lidoBytes() > 0 && c.totalBytes > 0 && velocidade > 0) {
            double estimativaTotal = (double) c.progresso.compactadoBytes() * c.totalBytes / c.progresso.lidoBytes();
            double restante = Math.max(0, estimativaTotal - c.bytesArquivo);
            previsao = (long) Math.ceil(restante / velocidade);
        }
        long recebidos = c.situacao.temArquivo() ? c.tamanhoArquivo : c.bytesArquivo;
        String arquivo = c.situacao.temArquivo() ? coletas.arquivoFinal(c).toAbsolutePath().toString() : null;
        return new Visao(c.id, c.cliente, c.nome, c.situacao.name(), c.mensagem, c.criadaEm, c.expiraEm,
                recebendo ? link : null, recebendo ? linkLocal : null, c.raiz, c.pastas, c.computador, c.navegador,
                c.totalArquivos, c.totalBytes, c.progresso, recebidos, c.brutoRecebido, c.arquivosRecebidos,
                velocidade, previsao, c.inicioEnvio, c.ultimoContato, c.concluidaEm, c.tamanhoArquivo, arquivo,
                c.resumo, c.conferencia, c.extracao, coletas.andamentoDoTrabalho(c.id).orElse(null),
                c.alterados == null ? List.of() : c.alterados.stream().limit(100).toList(),
                c.naoLidos == null ? List.of() : c.naoLidos.stream().limit(100).toList());
    }
}
