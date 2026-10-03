package br.com.conferenciaponto.infrastructure.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.util.Map;

/** Copia cada linha de log para o {@link RegistroDeLogs} (tela "Logs" ao vivo). */
public class AppenderMemoria extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent evento) {
        Map<String, String> contexto = evento.getMDCPropertyMap();
        IThrowableProxy erro = evento.getThrowableProxy();
        String origem = evento.getLoggerName();
        int ponto = origem == null ? -1 : origem.lastIndexOf('.');
        RegistroDeLogs.registrar(Instant.ofEpochMilli(evento.getTimeStamp()), evento.getLevel().toString(),
                contexto == null ? null : contexto.get(ContextoDeLog.USUARIO),
                contexto == null ? null : contexto.get(ContextoDeLog.PROTOCOLO),
                ponto < 0 ? origem : origem.substring(ponto + 1),
                evento.getFormattedMessage(),
                erro == null ? null : ThrowableProxyUtil.asString(erro));
    }
}
