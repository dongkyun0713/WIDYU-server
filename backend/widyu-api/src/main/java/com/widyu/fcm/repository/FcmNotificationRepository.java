package com.widyu.fcm.repository;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FcmNotificationRepository extends JpaRepository<FcmNotification, Long> {

    @Modifying(clearAutomatically = true)
    @Query("update FcmNotification n set n.isRead = true where n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId)")
    void markAllAsReadByMemberId(@Param("memberId") Long memberId);

    @Query("select n from FcmNotification n where n.id = :id and (n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId))")
    Optional<FcmNotification> findByIdAndMemberFcmToken_MemberId(Long id, Long memberId);

    @Query("select count(n) from FcmNotification n where n.isRead = false and (n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId))")
    long countByMemberFcmToken_MemberIdAndIsReadFalse(Long memberId);

    @Query("select count(n) from FcmNotification n where n.isRead = false and n.fcmCategory = :fcmCategory and (n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId))")
    long countByMemberFcmToken_MemberIdAndFcmCategoryAndIsReadFalse(Long memberId, FcmCategory fcmCategory);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE (n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId))
        AND (:cursor IS NULL OR n.id < :cursor)
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findNotificationsWithCursor(@Param("memberId") Long memberId,
                                                      @Param("cursor") Long cursor,
                                                      Pageable pageable);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE (n.recipientMember.id = :memberId or (n.recipientMember is null and n.memberFcmToken.member.id = :memberId))
        AND n.fcmCategory = :category
        AND (:cursor IS NULL OR n.id < :cursor)
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findNotificationsByCategoryWithCursor(@Param("memberId") Long memberId,
                                                               @Param("category") FcmCategory category,
                                                               @Param("cursor") Long cursor,
                                                               Pageable pageable);
}
