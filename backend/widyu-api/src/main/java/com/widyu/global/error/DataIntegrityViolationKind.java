package com.widyu.global.error;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * DB 무결성 위반을 클라이언트 입력 문제(길이 초과·중복)와 서버 데이터 문제로 나눈다(LLD-0085).
 * MySQL(dev·prod)과 H2(테스트)의 SQLState·벤더 코드를 함께 본다.
 */
public record DataIntegrityViolationKind(Type type, String field) {

    public enum Type { TOO_LONG, DUPLICATE, OTHER }

    private static final String SQL_STATE_TOO_LONG = "22001";
    private static final String SQL_STATE_UNIQUE_VIOLATION = "23505";
    private static final int MYSQL_DATA_TOO_LONG = 1406;
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;
    // MySQL: "Data too long for column 'schedule_name'", H2: "Value too long for column \"SCHEDULE_NAME ..."
    private static final Pattern COLUMN = Pattern.compile("column ['\"]([A-Za-z0-9_]+)");

    public static DataIntegrityViolationKind classify(final DataIntegrityViolationException exception) {
        if (exception instanceof DuplicateKeyException) {
            return new DataIntegrityViolationKind(Type.DUPLICATE, null);
        }
        final SQLException sqlException = findSqlException(exception);
        if (sqlException == null) {
            return new DataIntegrityViolationKind(Type.OTHER, null);
        }
        if (SQL_STATE_TOO_LONG.equals(sqlException.getSQLState()) || sqlException.getErrorCode() == MYSQL_DATA_TOO_LONG) {
            return new DataIntegrityViolationKind(Type.TOO_LONG, fieldOf(sqlException.getMessage()));
        }
        if (SQL_STATE_UNIQUE_VIOLATION.equals(sqlException.getSQLState())
                || sqlException.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
            return new DataIntegrityViolationKind(Type.DUPLICATE, null);
        }
        return new DataIntegrityViolationKind(Type.OTHER, null);
    }

    private static SQLException findSqlException(final Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    /** DB 컬럼 이름(schedule_name)을 요청 필드 이름(scheduleName)으로 바꾼다. 대부분의 DTO가 엔티티와 같은 이름을 쓴다. */
    static String fieldOf(final String message) {
        if (message == null) {
            return null;
        }
        final Matcher matcher = COLUMN.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        final String[] parts = matcher.group(1).toLowerCase(Locale.ROOT).split("_");
        final StringBuilder field = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                field.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return field.toString();
    }
}
