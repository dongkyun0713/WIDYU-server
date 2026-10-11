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
}
