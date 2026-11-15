package co.edu.corhuila.synkro.workflow.adapter.out.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.List;

/** Captures every record any logger writes while it is open, including throwable text. */
final class LogCapture implements AutoCloseable {
    private final ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Level previous = root.getLevel();
    private final ch.qos.logback.classic.Logger audit = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Slf4jAuditTrail.LOGGER_NAME);
    private final boolean separateAudit = !audit.isAdditive();

    LogCapture() {
        appender.start();
        root.addAppender(appender);
        if (separateAudit) audit.addAppender(appender);
        root.setLevel(Level.INFO);
    }

    List<ILoggingEvent> events() { return List.copyOf(appender.list); }

    /** Messages of the audit logger only. */
    List<String> auditLines() {
        return events().stream().filter(e -> Slf4jAuditTrail.LOGGER_NAME.equals(e.getLoggerName())).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Everything written by any logger: message, arguments, markers, MDC and stack traces. */
    String everything() {
        var out = new StringBuilder();
        for (ILoggingEvent event : events()) {
            out.append(event.getLoggerName()).append(' ').append(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap());
            if (event.getThrowableProxy() != null) out.append(' ').append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            out.append('\n');
        }
        return out.toString();
    }

    @Override public void close() {
        root.detachAppender(appender);
        if (separateAudit) audit.detachAppender(appender);
        root.setLevel(previous);
        appender.stop();
    }
}
