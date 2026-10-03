package br.com.conferenciaponto.infrastructure.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.sift.AbstractDiscriminator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Decide em qual arquivo cada linha de log cai: {@code <login>/<aaaa-mm-dd>/<hh>h} — um arquivo por usuário e
 * por hora (usado pelo SiftingAppender do {@code logback-spring.xml}).
 */
public class DiscriminadorUsuarioHora extends AbstractDiscriminator<ILoggingEvent> {

    public static final String CHAVE = "arquivo";

    private ZoneId fuso = ZoneId.systemDefault();

    @Override
    public String getDiscriminatingValue(ILoggingEvent evento) {
        String usuario = evento.getMDCPropertyMap() == null ? null : evento.getMDCPropertyMap().get(ContextoDeLog.USUARIO);
        return caminho(usuario, Instant.ofEpochMilli(evento.getTimeStamp()), fuso);
    }

    static String caminho(String usuario, Instant quando, ZoneId fuso) {
        ZonedDateTime hora = quando.atZone(fuso);
        return "%s/%s/%02dh".formatted(ContextoDeLog.pastaDo(usuario), hora.toLocalDate(), hora.getHour());
    }

    @Override
    public String getKey() {
        return CHAVE;
    }

    /** Fuso do sistema (a hora do nome do arquivo é a hora que as pessoas veem no relógio). */
    public void setFuso(String id) {
        try {
            this.fuso = ZoneId.of(id);
        } catch (RuntimeException e) {
            addWarn("Fuso \"" + id + "\" inválido: os logs por hora usam o fuso do computador.");
        }
    }
}
