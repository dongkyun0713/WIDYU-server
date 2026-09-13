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

    @Query("SELECT mp FROM MedicationProof mp " +
           "WHERE mp.member.id = :memberId " +
           "AND mp.verifiedAt BETWEEN :startDate AND :endDate")
    List<MedicationProof> findByMemberIdAndDateRange(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    List<MedicationProof> findAllByMember(Member member);

    boolean existsByMedicineScheduleAndVerifiedAtBetween(
            MedicineSchedule medicineSchedule,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );

    @Query("SELECT DISTINCT mp.member FROM MedicationProof mp " +
           "WHERE mp.verifiedAt BETWEEN :startDateTime AND :endDateTime")
    List<Member> findDistinctMembersByVerifiedAtBetween(
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );

    long countByMemberAndVerifiedAtBetween(
            Member member,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );

    @Query("SELECT DISTINCT mp.medicineSchedule.id FROM MedicationProof mp " +
           "WHERE mp.medicineSchedule.id IN :scheduleIds " +
           "AND mp.verifiedAt BETWEEN :startDate AND :endDate")
    List<Long> findVerifiedScheduleIds(
            @Param("scheduleIds") List<Long> scheduleIds,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );
}
