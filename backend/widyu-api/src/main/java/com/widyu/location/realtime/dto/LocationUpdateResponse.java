package com.widyu.location.realtime.dto;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public record LocationUpdateResponse(
    Long memberId,
    String name,
    String profileImage,
    Double latitude,
    Double longitude,
    LocalDateTime updatedAt,
    LocalDateTime stayStartTime,
    Long stayDurationMinutes,
    String locationType,
    String locationName
) {
    public static LocationUpdateResponse of(Long memberId, String name,
                                              String profileImage,
                                              Double latitude, Double longitude,
                                              LocalDateTime stayStartTime,
                                              String locationType,
                                              String locationName) {
        LocalDateTime now = LocalDateTime.now();
        long durationMinutes = ChronoUnit.MINUTES.between(stayStartTime, now);
        return new LocationUpdateResponse(
            memberId, name, profileImage,
            latitude, longitude, now,
            stayStartTime, durationMinutes, locationType, locationName
        );
    }

    /** 실시간 경로에 반영하지 않은 위치의 ACK. 받은 좌표만 돌려주고 체류 정보는 비운다(LLD-0114). */
    public static LocationUpdateResponse received(Long memberId, Double latitude, Double longitude) {
        return new LocationUpdateResponse(
            memberId, null, null,
            latitude, longitude, LocalDateTime.now(),
            null, null, null, null
        );
    }
}
