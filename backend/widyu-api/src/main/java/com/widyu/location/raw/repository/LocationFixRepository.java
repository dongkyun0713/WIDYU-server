package com.widyu.location.raw.repository;

import com.widyu.location.raw.LocationFix;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationFixRepository extends JpaRepository<LocationFix, Long> {

    boolean existsByDeviceIdAndSessionIdAndSeq(String deviceId, String sessionId, Long seq);

    /** 내보내기: 회차의 위치 원문을 잰 시각 순으로(LLD-0050 5.3). */
    java.util.List<LocationFix> findByRunIdOrderByTsMsAscSeqAsc(String runId);

    /** 시간대 내보내기: 한 회원의 구간 안 위치 원문을 잰 시각 순으로(LLD-0108 4절). 양 끝 포함. */
    java.util.List<LocationFix> findByMemberIdAndTsMsBetweenOrderByTsMsAscSeqAsc(
            Long memberId, Long startMs, Long endMs);
}
