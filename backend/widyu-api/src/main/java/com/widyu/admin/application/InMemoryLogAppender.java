package com.widyu.admin.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import com.widyu.admin.dto.response.AdminLogEntryResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    private static final int MAX_SIZE = 500;
    private static final Deque<AdminLogEntryResponse> buffer = new ArrayDeque<>();

    @Override
    protected synchronized void append(ILoggingEvent event) {
        if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
            if (buffer.size() >= MAX_SIZE) {
                buffer.pollFirst();
            }
            buffer.addLast(toResponse(event));
        }
    }

    public static synchronized List<AdminLogEntryResponse> getEntries(String level, int limit) {
        List<AdminLogEntryResponse> all = new ArrayList<>(buffer);
        List<AdminLogEntryResponse> filtered = all.stream()
                .filter(e -> level == null || e.level().equalsIgnoreCase(level))
                .toList();
        int from = Math.max(0, filtered.size() - limit);
        return new ArrayList<>(filtered.subList(from, filtered.size()))
                .reversed();
    }

    public static synchronized void clear() {
        buffer.clear();
    }

    private static AdminLogEntryResponse toResponse(ILoggingEvent event) {
        String shortLogger = shortenLogger(event.getLoggerName());
        String exception = null;
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            exception = ThrowableProxyUtil.asString(tp);
        }
        LocalDateTime time = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(event.getTimeStamp()), ZoneId.systemDefault());
        return new AdminLogEntryResponse(time, event.getLevel().toString(), shortLogger,
                event.getFormattedMessage(), exception);
    }

    private static String shortenLogger(String logger) {
        if (logger == null) return "";
        int dot = logger.lastIndexOf('.');
        if (dot >= 0) {
            return logger.substring(dot + 1);
        }
        return logger;
    }
}
