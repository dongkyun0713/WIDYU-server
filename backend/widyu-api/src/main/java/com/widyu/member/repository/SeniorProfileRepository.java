package com.widyu.member.repository;

import com.widyu.member.SeniorProfile;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SeniorProfileRepository extends JpaRepository<SeniorProfile, Long> {

    Optional<SeniorProfile> findByInviteCode(String inviteCode);

    Optional<SeniorProfile> findByMemberId(Long memberId);

    @Query("SELECT sp.family.id FROM SeniorProfile sp WHERE sp.member.id = :memberId")
    Optional<Long> findFamilyIdByMemberId(@Param("memberId") Long memberId);

    boolean existsByMemberId(Long memberId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SeniorProfile sp WHERE sp.id = :id")
    void deleteByIdDirectly(@Param("id") Long id);

    @Query("SELECT sp FROM SeniorProfile sp WHERE sp.inviteCode = :inviteCode AND sp.member.phoneNumber = :phoneNumber")
    Optional<SeniorProfile> findByInviteCodeAndMemberPhoneNumber(@Param("inviteCode") String inviteCode,
                                                                   @Param("phoneNumber") String phoneNumber);

    @Query("SELECT sp FROM SeniorProfile sp JOIN FETCH sp.member WHERE sp.family.id = :familyId")
    List<SeniorProfile> findAllByFamilyIdWithMember(@Param("familyId") Long familyId);

    List<SeniorProfile> findAllByFamilyId(Long familyId);

    @Transactional
    @Modifying
    @Query("UPDATE SeniorProfile sp SET sp.family = null WHERE sp.family.id = :familyId")
    void clearFamilyByFamilyId(@Param("familyId") Long familyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT sp FROM SeniorProfile sp WHERE sp.family.id = :familyId")
    List<SeniorProfile> findAllByFamilyIdWithLock(@Param("familyId") Long familyId);

    /** 긴급 수신자 스냅샷을 다시 만들 시니어 회원 ID. familyId가 null이면 전체다(LLD-0086 5.1). */
    @Query("SELECT sp.member.id FROM SeniorProfile sp WHERE (:familyId IS NULL OR sp.family.id = :familyId)")
    List<Long> findSeniorMemberIds(@Param("familyId") Long familyId);
}
