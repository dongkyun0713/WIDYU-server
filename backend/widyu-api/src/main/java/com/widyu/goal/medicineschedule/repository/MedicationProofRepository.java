package com.widyu.goal.medicineschedule.repository;

import com.widyu.member.Member;
import com.widyu.medicine.MedicationProof;
import com.widyu.medicine.MedicineSchedule;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicationProofRepository extends JpaRepository<MedicationProof, Long> {

    @Query("SELECT mp FROM MedicationProof mp " +
           "WHERE mp.medicineSchedule = :schedule " +
           "AND FUNCTION('DATE', mp.verifiedAt) = :date")
    List<MedicationProof> findByScheduleAndDate(
            @Param("schedule") MedicineSchedule schedule,
            @Param("date") LocalDate date
    );

    List<MedicationProof> findByMedicineScheduleAndVerifiedAtBetween(
            MedicineSchedule medicineSchedule,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );

    // 날짜 판정은 인증 시각(verifiedAt)이 아니라 복용 기준일(verifiedDate)로 한다. 중복 차단 유일 제약과 같은 기준이다.
    @Query("SELECT mp FROM MedicationProof mp " +
           "WHERE mp.member.id = :memberId " +
           "AND mp.verifiedDate BETWEEN :startDate AND :endDate")
    List<MedicationProof> findByMemberIdAndDateRange(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    List<MedicationProof> findAllByMember(Member member);

    boolean existsByMedicineScheduleAndVerifiedDate(MedicineSchedule medicineSchedule, LocalDate verifiedDate);

    long countByMemberAndVerifiedDate(Member member, LocalDate verifiedDate);

    @Query("SELECT DISTINCT mp.medicineSchedule.id FROM MedicationProof mp " +
           "WHERE mp.medicineSchedule.id IN :scheduleIds AND mp.verifiedDate = :date")
    List<Long> findVerifiedScheduleIds(
            @Param("scheduleIds") List<Long> scheduleIds,
            @Param("date") LocalDate date
    );

    @Query("SELECT DISTINCT mp.medicineSchedule.id FROM MedicationProof mp " +
           "WHERE mp.member.id = :memberId AND mp.verifiedDate = :date")
    List<Long> findVerifiedScheduleIdsByMemberAndDate(
            @Param("memberId") Long memberId,
            @Param("date") LocalDate date
    );

    // 그날 인증 중 완주 보너스를 받은 건이 있는지. 인증 1건의 적립 이력은 operationKey = prefix + proofId이고,
    // 보너스를 받은 건만 인증 1회분보다 큰 금액으로 남는다(LLD-0038 §11).
    @Query("SELECT CASE WHEN COUNT(ph) > 0 THEN true ELSE false END FROM PointHistory ph " +
           "WHERE ph.amount > :pointsPerMedication " +
           "AND ph.operationKey IN (" +
           "SELECT CONCAT(:operationKeyPrefix, CAST(mp.id AS String)) FROM MedicationProof mp " +
           "WHERE mp.member = :member AND mp.verifiedDate = :date)")
    boolean existsCompletionBonusByMemberAndVerifiedDate(
            @Param("member") Member member,
            @Param("date") LocalDate date,
            @Param("operationKeyPrefix") String operationKeyPrefix,
            @Param("pointsPerMedication") long pointsPerMedication
    );
}
