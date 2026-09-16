package com.widyu.fcm.repository;

import com.widyu.fcm.MemberFcmToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface MemberFcmTokenRepository extends JpaRepository<MemberFcmToken, Long> {
    @Modifying
    @Transactional
    @Query("update MemberFcmToken t set t.active = false, t.expiredAt = :now where t.id = :id and t.member.id = :recipientId and t.token = :token")
    int deactivateIfOwned(Long id, Long recipientId, String token, LocalDateTime now);

    @Query("select (count(t) > 0) from MemberFcmToken t where t.id = :id and t.member.id = :recipientId and t.active = true and t.member.status = com.widyu.global.entity.Status.ACTIVE")
    boolean isDeliverable(Long id, Long recipientId);

    Optional<MemberFcmToken> findByToken(String fcmToken);

    List<MemberFcmToken> findAllByMemberIdAndActiveTrue(Long id);

    List<MemberFcmToken> findAllByLastUsedAtBeforeAndActiveTrue(LocalDateTime threshold);

    long countByMemberIdAndActiveTrue(Long memberId);

    long countByActiveTrue();

    long countByActiveFalse();

    @Query("SELECT COUNT(DISTINCT t.member.id) FROM MemberFcmToken t WHERE t.active = true")
    long countDistinctMembersWithActiveToken();

    @Query("SELECT t FROM MemberFcmToken t JOIN FETCH t.member WHERE t.active = false ORDER BY t.expiredAt DESC")
    List<MemberFcmToken> findTop10InactiveOrderByExpiredAtDesc(Pageable pageable);
}
