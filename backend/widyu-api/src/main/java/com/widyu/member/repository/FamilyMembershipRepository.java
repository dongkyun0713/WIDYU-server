package com.widyu.member.repository;

import com.widyu.member.FamilyMembership;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamilyMembershipRepository extends JpaRepository<FamilyMembership, Long> {

    Optional<FamilyMembership> findByGuardianId(Long guardianId);

    @Query("SELECT fm.family.id FROM FamilyMembership fm WHERE fm.guardian.id = :guardianId")
    Optional<Long> findFamilyIdByGuardianId(@Param("guardianId") Long guardianId);

    void deleteByGuardianId(Long guardianId);

    Optional<FamilyMembership> findByFamilyIdAndGuardianId(Long familyId, Long guardianId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT fm FROM FamilyMembership fm WHERE fm.family.id = :familyId AND fm.guardian.id = :guardianId")
    Optional<FamilyMembership> findByFamilyIdAndGuardianIdForUpdate(
            @Param("familyId") Long familyId, @Param("guardianId") Long guardianId);

    boolean existsByFamilyIdAndGuardianId(Long familyId, Long guardianId);

    boolean existsByFamilyIdAndGuardianIdAndIsLeaderTrue(Long familyId, Long guardianId);

    boolean existsByFamilyIdAndIsLeaderTrue(Long familyId);

    long countByFamilyId(Long familyId);

    @Query("SELECT fm FROM FamilyMembership fm JOIN FETCH fm.guardian WHERE fm.family.id = :familyId")
    List<FamilyMembership> findAllByFamilyIdWithGuardian(@Param("familyId") Long familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT fm FROM FamilyMembership fm WHERE fm.family.id = :familyId ORDER BY fm.id ASC")
    List<FamilyMembership> findAllByFamilyIdForUpdate(@Param("familyId") Long familyId);

    @Query("SELECT fm FROM FamilyMembership fm JOIN FETCH fm.guardian WHERE fm.family.id = :familyId " +
           "ORDER BY fm.sortOrder ASC, fm.connectedAt ASC, fm.id ASC")
    List<FamilyMembership> findAllByFamilyIdWithGuardianOrdered(@Param("familyId") Long familyId);

    @Query("SELECT COALESCE(MAX(fm.sortOrder), -1) FROM FamilyMembership fm WHERE fm.family.id = :familyId")
    int findMaxSortOrderByFamilyId(@Param("familyId") Long familyId);

    @Query("SELECT COUNT(fm) > 0 FROM FamilyMembership fm " +
           "JOIN SeniorProfile sp ON sp.family.id = fm.family.id " +
           "WHERE fm.guardian.id = :guardianId AND sp.id = :seniorProfileId")
    boolean existsByGuardianIdAndSeniorProfileId(@Param("guardianId") Long guardianId,
                                                  @Param("seniorProfileId") Long seniorProfileId);

    @Query("SELECT COUNT(fm) > 0 FROM FamilyMembership fm " +
           "JOIN SeniorProfile sp ON sp.family.id = fm.family.id " +
           "WHERE fm.guardian.id = :guardianId AND sp.id = :seniorProfileId AND fm.isLeader = true")
    boolean existsByGuardianIdAndSeniorProfileIdAndIsLeaderTrue(@Param("guardianId") Long guardianId,
                                                                 @Param("seniorProfileId") Long seniorProfileId);

    /**
     * DB 장애 대비 긴급 수신자 스냅샷의 원천(LLD-0086 5.1). 시니어·보호자가 ACTIVE이고 활성 토큰이 있으며
     * 안전 알림을 끄지 않은 보호자만 한 번에 읽는다. 방장은 안전 알림이 필수라 설정과 무관하게 포함한다
     * ({@code NotificationSettingService.isNotificationEnabled}와 같은 규칙). familyId가 null이면 전체다.
     */
    @Query("SELECT new com.widyu.member.repository.EmergencyRecipientRow(" +
           "sm.id, sm.name, sm.profileImage, g.id, t.id, t.token) " +
           "FROM SeniorProfile sp JOIN sp.member sm " +
           "JOIN FamilyMembership fm ON fm.family = sp.family " +
           "JOIN fm.guardian g " +
           "JOIN MemberFcmToken t ON t.member = g " +
           "LEFT JOIN MemberNotificationSetting ns ON ns.member = g " +
           "AND ns.category = com.widyu.fcm.PushSettingGroup.SAFETY " +
           "WHERE sm.status = com.widyu.global.entity.Status.ACTIVE " +
           "AND g.status = com.widyu.global.entity.Status.ACTIVE " +
           "AND t.active = true " +
           "AND (fm.isLeader = true OR ns.id IS NULL OR ns.enabled = true) " +
           "AND (:familyId IS NULL OR sp.family.id = :familyId)")
    List<EmergencyRecipientRow> findEmergencyRecipients(@Param("familyId") Long familyId);
}
