package com.widyu.global.log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 스택트레이스를 원인(Caused by)까지 한 줄로 남긴다(LLD-0084).
 * 여러 줄로 찍으면 Loki에 줄마다 따로 저장돼 traceId로 한 번에 찾을 수 없고 다른 스레드 로그와 섞일 수 있다.
 */
public final class StackTraceLog {

    private static final int MAX_CAUSES = 5;
    private static final int MAX_CAUSE_FRAMES = 30;

    private StackTraceLog() {
    }

    public static String oneLine(final Throwable throwable) {
        final List<String> lines = new ArrayList<>();
        Arrays.stream(throwable.getStackTrace()).map(StackTraceElement::toString).forEach(lines::add);
        final Set<Throwable> seen = new HashSet<>();
        seen.add(throwable);
        Throwable cause = throwable.getCause();
        for (int depth = 0; cause != null && depth < MAX_CAUSES && seen.add(cause); depth++) {
            lines.add("Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
            Arrays.stream(cause.getStackTrace())
                    .limit(MAX_CAUSE_FRAMES)
                    .map(StackTraceElement::toString)
                    .forEach(lines::add);
            cause = cause.getCause();
        }
        return escapeLineBreaks(String.join("\n", lines));
    }

    static String escapeLineBreaks(final String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
