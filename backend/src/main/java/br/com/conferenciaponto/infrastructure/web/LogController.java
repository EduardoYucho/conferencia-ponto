package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.infrastructure.log.ArquivosDeLog;
import br.com.conferenciaponto.infrastructure.log.LogsProperties;
import br.com.conferenciaponto.infrastructure.log.RegistroDeLogs;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ErroDeTelaRequest;
import br.com.conferenciaponto.infrastructure.web.dto.LogAoVivoResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logs do sistema para o administrador e o aviso de erro de tela.
 * <pre>
 * GET  /api/v1/logs/ao-vivo?depois=&nivel=&login=&texto=&limite=&semConsultas=   últimas linhas (a tela consulta a cada 2 s)   (ADMIN)
 * GET  /api/v1/logs/arquivos                                       pastas por usuário → dias → horas            (ADMIN)
 * GET  /api/v1/logs/arquivo?login=&data=&hora=                     o log daquela pessoa naquela hora            (ADMIN)
 * POST /api/v1/erros-de-tela  {mensagem, tela, detalhe}            a tela avisa de um erro dela (qualquer perfil)
 * </pre>
 * Nas consultas o usuário é {@code login} (e não {@code usuario}, que nas outras rotas escolhe de quem são os dados).
 */
@RestController
public class LogController {

    private static final Logger log = LoggerFactory.getLogger("tela");
    private static final int LIMITE_PADRAO = 400;
    private static final int LIMITE_MAXIMO = 2000;
    /** No máximo este número de avisos de erro de tela por pessoa a cada minuto (uma tela em laço não enche o log). */
    private static final int AVISOS_POR_MINUTO = 20;

    private final ArquivosDeLog arquivos;
    private final LogsProperties properties;
    private final Map<String, int[]> avisosPorMinuto = new ConcurrentHashMap<>();

    public LogController(ArquivosDeLog arquivos, LogsProperties properties) {
        this.arquivos = arquivos;
        this.properties = properties;
    }

    @GetMapping("/api/v1/logs/ao-vivo")
    public ApiResponse<LogAoVivoResponse> aoVivo(@RequestParam(defaultValue = "0") long depois,
                                                 @RequestParam(required = false) String nivel,
                                                 @RequestParam(required = false) String login,
                                                 @RequestParam(required = false) String texto,
                                                 @RequestParam(required = false) Integer limite,
                                                 @RequestParam(defaultValue = "false") boolean semConsultas) {
        int maximo = limite == null ? LIMITE_PADRAO : Math.max(1, Math.min(limite, LIMITE_MAXIMO));
        long ultima = RegistroDeLogs.ultimaSequencia();
        return ApiResponse.ok(new LogAoVivoResponse(ultima, RegistroDeLogs.depoisDe(depois, nivel, login, texto, maximo, semConsultas)));
    }

    /**
     * @param pasta        onde os arquivos ficam no computador do servidor (para abrir direto, se preciso)
     * @param diasGuardados depois desse prazo os arquivos são apagados sozinhos
     */
    public record ArquivosResponse(String pasta, int diasGuardados, List<ArquivosDeLog.Pasta> usuarios) {
    }

    @GetMapping("/api/v1/logs/arquivos")
    public ApiResponse<ArquivosResponse> arquivos() {
        return ApiResponse.ok(new ArquivosResponse(properties.pastaDosUsuarios().toString(), properties.dias(),
                arquivos.listar()));
    }

    @GetMapping("/api/v1/logs/arquivo")
    public ApiResponse<ArquivosDeLog.Conteudo> arquivo(@RequestParam String login,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
                                                       @RequestParam int hora) {
        return ApiResponse.ok(arquivos.ler(login, data, hora));
    }

    /** Erro que aconteceu no navegador: fica registrado no log da pessoa, para o administrador conseguir investigar. */
    @PostMapping("/api/v1/erros-de-tela")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> erroDeTela(@RequestBody ErroDeTelaRequest r, HttpServletRequest requisicao) {
        String quem = requisicao.getUserPrincipal() == null ? "?" : requisicao.getUserPrincipal().getName();
        if (dentroDoLimite(quem)) {
            log.warn("Erro na tela {}: {}{}", cortar(r.tela(), 200), cortar(r.mensagem(), 500),
                    r.detalhe() == null || r.detalhe().isBlank() ? "" : "\n" + cortar(r.detalhe(), 4000));
        }
        return ApiResponse.ok(null);
    }

    private boolean dentroDoLimite(String quem) {
        int minuto = (int) (Instant.now().getEpochSecond() / 60);
        int[] contador = avisosPorMinuto.compute(quem, (k, atual) ->
                atual == null || atual[0] != minuto ? new int[]{minuto, 1} : new int[]{minuto, atual[1] + 1});
        return contador[1] <= AVISOS_POR_MINUTO;
    }

    private static String cortar(String texto, int maximo) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.strip();
        return limpo.length() <= maximo ? limpo : limpo.substring(0, maximo) + "…";
    }
}
