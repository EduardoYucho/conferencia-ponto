package br.com.conferenciaponto.infrastructure.log;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Para as tarefas em segundo plano que só conhecem o id da pessoa (gravação da planilha, reconferência com o
 * RH): descobre o login e põe no contexto do log, para as linhas caírem na pasta dela.
 */
@Component
public class DonoDoLog {

    private final UsuarioRepository usuarios;
    /** O login não muda depois do cadastro: pode ficar guardado. */
    private final Map<UUID, String> logins = new ConcurrentHashMap<>();

    public DonoDoLog(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Executa com a pessoa no contexto do log; sem id (vale para todos), as linhas ficam em "sistema". */
    public void executar(UUID usuarioId, Runnable tarefa) {
        ContextoDeLog.comUsuario(loginDe(usuarioId), tarefa);
    }

    public String loginDe(UUID usuarioId) {
        if (usuarioId == null) {
            return null;
        }
        String conhecido = logins.get(usuarioId);
        if (conhecido != null) {
            return conhecido;
        }
        try {
            String login = usuarios.buscarPorId(usuarioId).map(Usuario::login).orElse(null);
            if (login != null) {
                logins.put(usuarioId, login);
            }
            return login;
        } catch (RuntimeException e) {
            return null; // o log não pode derrubar a tarefa: a linha vai para "sistema"
        }
    }
}
