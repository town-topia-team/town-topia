package com.alex.test.village.stuckwatch;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Stuck catcher: the mod's last few thousand log lines (debug and up), so a
 * report can quote what the log said about the caught villager. Attached only
 * while {@code /villager stuckwatch} is on, like the chat relay's appender.
 */
final class LogTail {

    private static final String LOGGER_PREFIX = "com.alex.test";
    private static final String APPENDER_NAME = "DynamicVillagesStuckTail";
    private static final int KEEP = 4000;

    record Line(long millis, String level, String logger, String message) {}

    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static TailAppender appender;

    private LogTail() {}

    static synchronized void attach() {
        if (appender != null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        appender = new TailAppender();
        appender.start();
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
    }

    static synchronized void detach() {
        if (appender == null) return;
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.getRootLogger().removeAppender(APPENDER_NAME);
        config.getAppenders().remove(APPENDER_NAME);
        ctx.updateLoggers();
        appender.stop();
        appender = null;
        synchronized (LINES) {
            LINES.clear();
        }
    }

    /** Lines since {@code sinceMillis} that contain any of {@code needles}, oldest first. */
    static List<Line> matching(long sinceMillis, String... needles) {
        List<Line> out = new ArrayList<>();
        synchronized (LINES) {
            for (Line line : LINES) {
                if (line.millis() < sinceMillis) continue;
                for (String needle : needles) {
                    if (needle != null && !needle.isEmpty() && line.message().contains(needle)) {
                        out.add(line);
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static final class TailAppender extends AbstractAppender {
        TailAppender() {
            super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            String logger = event.getLoggerName();
            if (logger == null || !logger.startsWith(LOGGER_PREFIX)) return;
            // Read everything now: the event object may be reused once we return.
            String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
            int dot = logger.lastIndexOf('.');
            Line line = new Line(event.getTimeMillis(), event.getLevel().name(),
                    dot < 0 ? logger : logger.substring(dot + 1), message);
            synchronized (LINES) {
                LINES.addLast(line);
                while (LINES.size() > KEEP) LINES.removeFirst();
            }
        }
    }
}
