package com.widyu.member.repository;

import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MemberRepository extends JpaRepository<Member, Long>, MemberRepositoryCustom {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Member m WHERE m.id = :memberId")
    Optional<Member> findByIdForUpdate(@Param("memberId") Long memberId);

    Optional<Member> findByPhoneNumber(String phoneNumber);
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
}
