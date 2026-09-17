package com.widyu.goal.medicineschedule.repository;

import com.widyu.global.entity.Status;
import com.widyu.medicine.MedicineSchedule;
import com.widyu.member.Member;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicineScheduleRepository extends JpaRepository<MedicineSchedule, Long> {

    @Query("SELECT DISTINCT ms FROM MedicineSchedule ms " +
           "LEFT JOIN FETCH ms.categories c " +
           "WHERE ms.id = :scheduleId AND ms.status = :status")
    Optional<MedicineSchedule> findByIdAndStatusWithDetails(
            @Param("scheduleId") Long scheduleId,
            @Param("status") Status status
    );

    // 특정 날짜에 유효했던 스케줄 버전만 조회 (일자별 조회용)
    @Query("SELECT DISTINCT ms FROM MedicineSchedule ms " +
           "LEFT JOIN FETCH ms.categories c " +
           "WHERE ms.member = :member AND ms.status = :status " +
           "AND (ms.effectiveFrom IS NULL OR ms.effectiveFrom <= :date) " +
           "AND (ms.effectiveTo IS NULL OR ms.effectiveTo >= :date) " +
           "ORDER BY ms.alarmTime ASC")
    List<MedicineSchedule> findEffectiveByMemberAndDateWithDetails(
            @Param("member") Member member,
            @Param("status") Status status,
            @Param("date") LocalDate date
    );

    // 현재 유효한(effectiveTo IS NULL) 최신 스케줄만 조회 (홈 화면용)
    @Query("SELECT DISTINCT ms FROM MedicineSchedule ms " +
           "LEFT JOIN FETCH ms.categories c " +
           "WHERE ms.member = :member AND ms.status = :status " +
           "AND ms.effectiveTo IS NULL " +
           "ORDER BY ms.alarmTime ASC")
    List<MedicineSchedule> findCurrentByMemberWithDetails(
            @Param("member") Member member,
            @Param("status") Status status
    );

    // [start, end] 기간과 겹치는 모든 스케줄 버전 조회 (월별 통계에서 날짜별 유효 수 계산용)
    @Query("SELECT ms FROM MedicineSchedule ms " +
           "WHERE ms.member = :member AND ms.status = :status " +
           "AND (ms.effectiveFrom IS NULL OR ms.effectiveFrom <= :end) " +
           "AND (ms.effectiveTo IS NULL OR ms.effectiveTo >= :start)")
    List<MedicineSchedule> findEffectiveByMemberAndDateRange(
            @Param("member") Member member,
            @Param("status") Status status,
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );

    // 특정 알람 시각 + 특정 날짜에 유효한 스케줄만 조회 (복약 알림용)
    @Query("SELECT DISTINCT ms FROM MedicineSchedule ms " +
           "LEFT JOIN FETCH ms.member " +
           "WHERE ms.alarmTime = :alarmTime AND ms.status = :status " +
           "AND (ms.effectiveFrom IS NULL OR ms.effectiveFrom <= :date) " +
           "AND (ms.effectiveTo IS NULL OR ms.effectiveTo >= :date)")
    List<MedicineSchedule> findByAlarmTimeAndStatusEffectiveOn(
            @Param("alarmTime") LocalTime alarmTime,
            @Param("status") Status status,
            @Param("date") LocalDate date
    );

    // 특정 날짜에 유효했던 스케줄 개수 (복약 포인트 완주 보너스 판정의 분모)
    @Query("SELECT COUNT(ms) FROM MedicineSchedule ms " +
           "WHERE ms.member = :member AND ms.status = :status " +
           "AND (ms.effectiveFrom IS NULL OR ms.effectiveFrom <= :date) " +
           "AND (ms.effectiveTo IS NULL OR ms.effectiveTo >= :date)")
    long countEffectiveByMemberAndDate(
            @Param("member") Member member,
            @Param("status") Status status,
            @Param("date") LocalDate date
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE medicine_schedule
               SET effective_from = CAST(created_at AS DATE)
             WHERE effective_from IS NULL
            """, nativeQuery = true)
    int backfillMissingEffectiveFrom();
}
