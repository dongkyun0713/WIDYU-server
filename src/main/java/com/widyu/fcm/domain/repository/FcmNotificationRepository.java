package com.widyu.fcm.domain.repository;

import com.widyu.fcm.domain.FcmNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FcmNotificationRepository extends JpaRepository<FcmNotification, Long> {
    List<FcmNotification> findAllByMemberFcmToken_MemberIdOrderByCreatedAtDesc(Long id);

    @Modifying(clearAutomatically = true)
    @Query("update FcmNotification n set n.isRead = true where n.memberFcmToken.member.id = :memberId")
    void markAllAsReadByMemberId(@Param("memberId") Long memberId);

    Optional<FcmNotification> findByIdAndMemberFcmToken_MemberId(Long id, Long memberId);
}

