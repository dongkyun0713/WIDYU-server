package com.widyu.sensor.repository;

import com.widyu.sensor.ClockMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ClockMappingRepository extends JpaRepository<ClockMapping, Long> {

    Optional<ClockMapping> findByClockMappingId(String clockMappingId);

    /**
     * 관측 범위를 이 배치까지 덮도록 넓힌다. 읽고 비교해 저장하면 동시 갱신에서 한쪽이 덮이므로
     * 한 문장으로 처리한다(LLD-0044 5.1 4단계).
     */
    @Modifying
    @Query("""
            UPDATE ClockMapping m
               SET m.observedMinElapsedNs =
                       CASE WHEN m.observedMinElapsedNs < :min THEN m.observedMinElapsedNs ELSE :min END,
                   m.observedMaxElapsedNs =
                       CASE WHEN m.observedMaxElapsedNs > :max THEN m.observedMaxElapsedNs ELSE :max END,
                   m.lastSeenAtMs = :now
             WHERE m.clockMappingId = :clockMappingId
            """)
    int widenObservedRange(
            @Param("clockMappingId") String clockMappingId,
            @Param("min") long min,
            @Param("max") long max,
            @Param("now") long now);
}
