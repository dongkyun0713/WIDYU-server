package com.widyu.heart.repository;

import com.widyu.heart.HeartRateEvent;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface HeartRateEventRepository extends JpaRepository<HeartRateEvent, Long> {

    boolean existsByMemberIdAndMeasuredAt(Long memberId, LocalDateTime measuredAt);

    List<HeartRateEvent> findByMemberIdAndMeasuredAtAfterOrderByMeasuredAtAsc(Long memberId, LocalDateTime since);

    List<HeartRateEvent> findTop5ByMemberIdOrderByMeasuredAtDesc(Long memberId);

    Optional<HeartRateEvent> findFirstByMemberIdOrderByMeasuredAtDesc(Long memberId);

    @Query("SELECT MAX(h.heartRate) FROM HeartRateEvent h WHERE h.member.id = :memberId AND h.measuredAt > :since")
    Optional<Integer> findMaxHeartRateByMemberIdSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

    @Query("SELECT MIN(h.heartRate) FROM HeartRateEvent h WHERE h.member.id = :memberId AND h.measuredAt > :since")
    Optional<Integer> findMinHeartRateByMemberIdSince(@Param("memberId") Long memberId, @Param("since") LocalDateTime since);

    @Modifying
    @Query("DELETE FROM HeartRateEvent h WHERE h.measuredAt < :before")
    int deleteByMeasuredAtBefore(@Param("before") LocalDateTime before);

    @Modifying
    @Query("DELETE FROM HeartRateEvent h WHERE h.measuredAt < :before AND h.member.id NOT IN :memberIds")
    int deleteByMeasuredAtBeforeAndMemberIdNotIn(
            @Param("before") LocalDateTime before, @Param("memberIds") Collection<Long> memberIds);
}
