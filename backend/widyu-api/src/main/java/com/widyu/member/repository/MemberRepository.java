package com.widyu.member.repository;

import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long>, MemberRepositoryCustom {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);

    // 빈 번호일 때만 채운다. 확인과 갱신을 한 문장으로 해 동시 요청이 먼저 채운 번호를 덮어쓰지 않는다 (LLD-0094)
    @Modifying
    @Query("UPDATE Member m SET m.phoneNumber = :phoneNumber "
            + "WHERE m.id = :memberId AND (m.phoneNumber IS NULL OR TRIM(m.phoneNumber) = '')")
    int fillPhoneNumberIfEmpty(@Param("memberId") Long memberId, @Param("phoneNumber") String phoneNumber);

    Optional<Member> findByPhoneNumber(String phoneNumber);
    boolean existsByPhoneNumber(String phoneNumber);
    Optional<Member> findByPhoneNumberAndName(String phoneNumber, String name);
    Optional<Member> findByPhoneNumberAndNameAndLocalAccount_Email(String phoneNumber, String name, String email);
    Optional<Member> findBySocialAccounts_Email(String email);
    Optional<Member> findBySocialAccounts_EmailAndSocialAccounts_Provider(String email, String provider);
    long countByType(MemberType type);
    long countByCreatedAtAfter(LocalDateTime dateTime);
    long countByRoleNot(MemberRole role);
    long countByTypeAndRoleNot(MemberType type, MemberRole role);
    long countByCreatedAtAfterAndRoleNot(LocalDateTime dateTime, MemberRole role);
    long countByCreatedAtBetweenAndRoleNot(LocalDateTime start, LocalDateTime end, MemberRole role);
    List<Member> findTop20ByNameContainingOrderByIdDesc(String name);
    List<Member> findTop50ByOrderByIdDesc();
    Page<Member> findByNameContainingOrderByIdDesc(String name, Pageable pageable);
    Page<Member> findAllByOrderByIdDesc(Pageable pageable);
    List<Member> findTop3ByPhoneNumberContainingOrderByIdDesc(String phoneNumber);
    boolean existsByPhoneNumberAndIdNot(String phoneNumber, Long id);

    // 전화번호 유일 제약이 걸리기 전 기존 중복을 단건 조회 예외(500) 없이 알아채려고 두 건까지 읽는다 (LLD-0116)
    List<Member> findTop2ByPhoneNumber(String phoneNumber);
    List<Member> findTop2ByPhoneNumberAndName(String phoneNumber, String name);
    boolean existsByIdAndStatus(Long id, Status status);

    @Query("SELECT m.id FROM Member m WHERE m.id IN :ids AND m.status = com.widyu.global.entity.Status.ACTIVE")
    Set<Long> findActiveIdsIn(@Param("ids") Collection<Long> ids);
}
