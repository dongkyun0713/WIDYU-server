package com.widyu.global.config;

import com.widyu.global.log.StackTraceLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.ErrorHandler;

/**
 * 스케줄러에서 처리되지 않은 예외를 알림이 읽을 수 있는 한 줄로 남긴다(LLD-0084).
 * Spring 기본 핸들러는 "Unexpected error occurred in scheduled task"만 남겨 어떤 작업이 왜 실패했는지 알림에 실리지 않는다.
 * 스택은 여러 줄로 찍으면 다른 작업 로그와 섞이므로 원인까지 한 줄에 담는다. 기본 핸들러처럼 예외를 삼켜 다음 실행이 계속되게 한다.
 */
@Slf4j
public class SchedulerErrorHandler implements ErrorHandler {

    private static final String APP_PACKAGE = "com.widyu.";

    @Override
    public void handleError(final Throwable throwable) {
        log.error(toLogString(throwable));
    }

    static String toLogString(final Throwable throwable) {
        return String.format(
                "[SCHEDULER] Task=%s ExceptionType=%s Message=%s StackTrace=%s",
                taskName(throwable),
                throwable.getClass().getSimpleName(),
                singleLine(throwable.getMessage()),
                StackTraceLog.oneLine(throwable)
        );
    }

    /** 스택에서 가장 바깥쪽 앱 프레임이 @Scheduled 메서드다. 프록시 접미사는 뗀다. */
    static String taskName(final Throwable throwable) {
        final StackTraceElement[] frames = throwable.getStackTrace();
        for (int i = frames.length - 1; i >= 0; i--) {
            final String className = frames[i].getClassName();
            if (className.startsWith(APP_PACKAGE)) {
                return simpleName(className) + "." + frames[i].getMethodName();
            }
        }
        return "unknown";
    }

    private static String simpleName(final String className) {
        final String simpleName = className.substring(className.lastIndexOf('.') + 1);
        final int nested = simpleName.indexOf('$');
        if (nested < 0) {
            return simpleName;
        }
        return simpleName.substring(0, nested);
    }

    private static String singleLine(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }
}
