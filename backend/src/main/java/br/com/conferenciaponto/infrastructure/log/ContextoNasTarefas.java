package br.com.conferenciaponto.infrastructure.log;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Leva o contexto do log (usuário e protocolo) da thread que pediu a tarefa para a thread que a executa.
 * Sem isto, o que roda em segundo plano por causa de uma ação da pessoa ficaria registrado como "sistema".
 */
public class ContextoNasTarefas implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable tarefa) {
        Map<String, String> contexto = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> anterior = MDC.getCopyOfContextMap();
            if (contexto == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(contexto);
            }
            try {
                tarefa.run();
            } finally {
                if (anterior == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(anterior);
                }
            }
        };
    }
}
