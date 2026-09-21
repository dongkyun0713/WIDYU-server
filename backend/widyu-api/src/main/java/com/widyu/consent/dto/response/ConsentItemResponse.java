package com.widyu.consent.dto.response;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.ConsentRecord;
import java.time.LocalDateTime;

/** 항목 하나의 현재 상태(LLD-0055 3절). 항목별 최신 행을 그대로 옮긴다. */
public record ConsentItemResponse(
        ConsentKey key,
        boolean granted,
        String version,
        LocalDateTime recordedAt
) {

    public static ConsentItemResponse from(ConsentRecord record) {
        return new ConsentItemResponse(
                record.getConsentKey(), record.isGranted(), record.getVersion(), record.getRecordedAt());
    }
}
