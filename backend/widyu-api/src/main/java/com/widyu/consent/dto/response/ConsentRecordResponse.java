package com.widyu.consent.dto.response;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.ConsentRecord;
import com.widyu.consent.ConsentSource;
import java.time.LocalDateTime;

/**
 * 관리자 이력 조회의 한 행(LLD-0055 3절). 이름·전화번호 같은 회원 식별 정보는 싣지 않는다.
 * 「어느 항목에 언제 동의했고 언제 철회했는지」를 답하는 데 필요하지 않다.
 */
public record ConsentRecordResponse(
        ConsentKey key,
        boolean granted,
        String version,
        LocalDateTime recordedAt,
        ConsentSource source
) {

    public static ConsentRecordResponse from(ConsentRecord record) {
        return new ConsentRecordResponse(
                record.getConsentKey(),
                record.isGranted(),
                record.getVersion(),
                record.getRecordedAt(),
                record.getSource());
    }
}
