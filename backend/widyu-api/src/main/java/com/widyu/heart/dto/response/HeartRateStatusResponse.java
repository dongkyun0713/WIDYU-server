package com.widyu.heart.dto.response;

import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import java.time.LocalDateTime;

public record HeartRateStatusResponse(
        Long memberId,
        HeartRateStatus heartRateStatus,
        Integer heartRate,
        LocalDateTime measuredAt
) {
    public static HeartRateStatusResponse from(HeartRateResult result) {
        return new HeartRateStatusResponse(
                result.getMemberId(),
                result.getStatus(),
                result.getHeartRate(),
                result.getMeasuredAt()
        );
    }

    public static HeartRateStatusResponse from(Long memberId, HeartRateEvent event) {
        return new HeartRateStatusResponse(
                memberId,
                event.getStatus(),
                event.getHeartRate(),
                event.getMeasuredAt()
        );
    }

    /** DB에 저장하지 못한 측정값을 AI 판정만으로 응답할 때 쓴다(LLD-0086 5.2). */
    public static HeartRateStatusResponse of(
            Long memberId, HeartRateStatus status, Integer heartRate, LocalDateTime measuredAt) {
        return new HeartRateStatusResponse(memberId, status, heartRate, measuredAt);
    }

    public static HeartRateStatusResponse unknown(Long memberId) {
        return new HeartRateStatusResponse(
                memberId,
                HeartRateStatus.UNKNOWN,
                null,
                null
        );
    }
}
