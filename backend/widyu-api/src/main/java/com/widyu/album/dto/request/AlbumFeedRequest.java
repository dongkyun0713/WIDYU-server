package com.widyu.album.dto.request;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

public record AlbumFeedRequest(
        LocalDateTime lastCreatedAt,
        Long lastAlbumId,
        String date
) {
    public boolean hasCursor() {
        return lastCreatedAt != null && lastAlbumId != null;
    }

    public boolean hasDate() {
        return date != null && !date.trim().isEmpty();
    }

    public LocalDate parsedDate() {
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "날짜 형식은 yyyy-MM-dd 이어야 합니다.");
        }
    }

    public static AlbumFeedRequest from(String cursor, String date) {
        if (cursor == null || cursor.isBlank()) {
            return new AlbumFeedRequest(null, null, date);
        }

        String[] parts = cursor.split("\\|", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw invalidCursorException();
        }

        LocalDateTime lastCreatedAt = parseCreatedAt(parts[0]);
        Long lastAlbumId = parseAlbumId(parts[1]);
        return new AlbumFeedRequest(lastCreatedAt, lastAlbumId, date);
    }

    private static LocalDateTime parseCreatedAt(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw invalidCursorException();
        }
    }

    private static Long parseAlbumId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalidCursorException();
        }
    }

    private static BusinessException invalidCursorException() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "커서 형식은 createdAt|albumId 이어야 합니다.");
    }
}
