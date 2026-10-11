package com.widyu.goal.healthschedule.repository;

import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.global.entity.Status;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HealthScheduleRepository extends JpaRepository<HealthSchedule, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT h FROM HealthSchedule h WHERE h.id = :id")
    Optional<HealthSchedule> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT h FROM HealthSchedule h WHERE h.member.id = :memberId AND h.scheduledAt >= :startDate AND h.scheduledAt < :endDate")
    List<HealthSchedule> findByMemberIdAndYearMonth(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    Optional<HealthSchedule> findFirstByMemberIdAndScheduledAtAfterOrderByScheduledAtAsc(
            Long memberId, LocalDateTime after);

    @Query("SELECT h FROM HealthSchedule h WHERE h.member.id = :memberId AND h.scheduledAt >= :startOfDay AND h.scheduledAt < :startOfNextDay")
    List<HealthSchedule> findByMemberIdAndDate(
            @Param("memberId") Long memberId,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("startOfNextDay") LocalDateTime startOfNextDay
    );

    @Query("SELECT h FROM HealthSchedule h WHERE h.member.id = :memberId AND h.progressStatus = :status AND h.scheduledAt >= :startOfDay AND h.scheduledAt < :startOfNextDay")
    List<HealthSchedule> findByMemberIdAndStatusAndDate(
            @Param("memberId") Long memberId,
            @Param("status") ProgressStatus status,
            @Param("startOfDay") LocalDateTime startOfDay,
            @Param("startOfNextDay") LocalDateTime startOfNextDay
    );

    @Query("SELECT h FROM HealthSchedule h WHERE h.member.id = :memberId AND h.scheduledAt >= :startDate AND h.scheduledAt < :endDate ORDER BY h.scheduledAt ASC")
    List<HealthSchedule> findByMemberIdAndWeek(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT h FROM HealthSchedule h WHERE h.progressStatus = :status AND h.scheduledAt >= :startDate AND h.scheduledAt < :endDate")
    List<HealthSchedule> findByStatusAndDateRange(
            @Param("status") ProgressStatus status,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT h FROM HealthSchedule h WHERE h.progressStatus = 'UPCOMING' AND h.scheduledAt >= :startDate AND h.scheduledAt < :endDate")
    List<HealthSchedule> findUpcomingSchedulesInTimeRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT h FROM HealthSchedule h WHERE h.progressStatus = :status AND h.scheduledAt < :beforeDate")
    List<HealthSchedule> findByStatusAndScheduledAtBefore(
            @Param("status") ProgressStatus status,
            @Param("beforeDate") LocalDateTime beforeDate
    );
}
