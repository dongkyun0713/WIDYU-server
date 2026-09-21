package com.widyu.consent.dto.response;

import com.widyu.consent.ConsentRecord;
import java.util.List;

/**
 * 회원의 현재 동의 상태(LLD-0055 3절). 기록이 한 번도 없는 항목은 목록에 넣지 않는다.
 * 「동의하지 않았다」와 「아직 묻지 않았다」는 다르기 때문이다.
 */
public record ConsentStateResponse(Long memberId, List<ConsentItemResponse> items) {

    public static ConsentStateResponse of(Long memberId, List<ConsentRecord> latestRecords) {
        return new ConsentStateResponse(
                memberId, latestRecords.stream().map(ConsentItemResponse::from).toList());
    }
}
