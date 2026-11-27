package net.axther.aftrhrs.gametest.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches errors logged while a test runs (Feather catches exceptions in commands, menu buttons and listeners and
 * only logs them, so a test has to look at the log to see them). Errors and anything logged with a stack trace count.
 */
public final class LogWatch implements AutoCloseable {
    private final List<String> seen = new ArrayList<>();
    private final AbstractAppender appender;
    private final LoggerContext ctx;

    public LogWatch() {
        ctx = (LoggerContext) LogManager.getContext(false);
        appender = new AbstractAppender("btcore-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                if (!e.getLevel().isMoreSpecificThan(Level.ERROR) && e.getThrown() == null) return;
                if (!"Server thread".equals(e.getThreadName()) && !e.getThreadName().startsWith("AftrHrs")) return;
                // Other tests failing (they run side by side) are theirs to report.
                if (e.getLoggerName().contains("gametest") || e.getLoggerName().contains("TestAnnotationLocator")) return;
                String msg = e.getMessage().getFormattedMessage();
                Throwable t = e.getThrown();
                synchronized (seen) {
                    seen.add(e.getLoggerName() + ": " + msg + (t == null ? "" : " <- " + t + at(t)));
                }
            }
        };
        appender.start();
        ctx.getConfiguration().getRootLogger().addAppender(appender, Level.WARN, null);
        ctx.updateLoggers();
    }

    private static String at(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        StackTraceElement[] st = t.getStackTrace();
        for (StackTraceElement e : st) if (e.getClassName().startsWith("net.axther")) return " at " + e;
        return st.length > 0 ? " at " + st[0] : "";
    }

    public List<String> errors() {
        synchronized (seen) {
            return new ArrayList<>(seen);
        }
    }

    public void clear() {
        synchronized (seen) {
            seen.clear();
        }
    }

    @Override
    public void close() {
        ctx.getConfiguration().getRootLogger().removeAppender(appender.getName());
        ctx.updateLoggers();
        appender.stop();
    }
}
