package com.widyu.location.raw.application;

import com.widyu.location.realtime.dto.LocationUpdateRequest;

/** LLD-0048 3절 v2 페이로드 고정값. 테스트마다 바꾸는 값만 인자로 받는다. */
final class LocationFixFixture {

    static final Long MEMBER_ID = 1L;
    static final String DEVICE_ID = "ph-9c1";
    static final String SESSION_ID = "s-2026";
    static final Long SEQ = 5120L;
    static final Long TS_MS = 1_760_000_000_123L;

    private LocationFixFixture() {
    }

    static LocationUpdateRequest rawFix(String reason, Double latitude, Double accuracyM, Boolean isMock) {
        return new LocationUpdateRequest(
                MEMBER_ID, null, null, null,
                2, DEVICE_ID, SESSION_ID, SEQ, null, null, null,
                latitude, 126.9707, accuracyM, 0.9, 0.5, 212.0, 41.2, "fused", isMock, TS_MS, reason);
    }
}
