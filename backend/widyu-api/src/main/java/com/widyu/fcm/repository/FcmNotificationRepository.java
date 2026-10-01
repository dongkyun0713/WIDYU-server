package com.widyu.fcm.repository;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.NotificationType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FcmNotificationRepository extends JpaRepository<FcmNotification, Long> {

    Optional<FcmNotification> findByRecipientMemberIdAndEventId(Long recipientMemberId, String eventId);

    Optional<FcmNotification> findByIdAndRecipientMemberId(Long id, Long recipientMemberId);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
          AND (n.expiresAt IS NULL OR n.expiresAt > :now)
          AND (:cursor IS NULL OR n.id < :cursor)
          AND (:unreadOnly = false OR n.isRead = false)
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findCenterPage(@Param("memberId") Long memberId,
                                         @Param("now") LocalDateTime now,
                                         @Param("cursor") Long cursor,
                                         @Param("unreadOnly") boolean unreadOnly,
                                         Pageable pageable);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
          AND (n.expiresAt IS NULL OR n.expiresAt > :now)
          AND (:cursor IS NULL OR n.id < :cursor)
          AND (n.type IN :types OR (n.type IS NULL AND n.fcmCategory IN :legacyCategories))
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findCenterPageByCategory(@Param("memberId") Long memberId,
                                                   @Param("now") LocalDateTime now,
                                                   @Param("cursor") Long cursor,
                                                   @Param("types") List<NotificationType> types,
                                                   @Param("legacyCategories") List<FcmCategory> legacyCategories,
                                                   Pageable pageable);

    @Query("""
        SELECT COUNT(n) FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
          AND (n.expiresAt IS NULL OR n.expiresAt > :now)
          AND n.isRead = false
        """)
    long countCenterUnread(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);

    @Query("""
        SELECT COUNT(n) FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
          AND (n.expiresAt IS NULL OR n.expiresAt > :now)
          AND n.isRead = false
          AND (n.type IN :types OR (n.type IS NULL AND n.fcmCategory IN :legacyCategories))
        """)
    long countCenterUnreadByCategory(@Param("memberId") Long memberId,
                                     @Param("now") LocalDateTime now,
                                     @Param("types") List<NotificationType> types,
                                     @Param("legacyCategories") List<FcmCategory> legacyCategories);

    @Modifying(clearAutomatically = true)
    @Query("update FcmNotification n set n.isRead = true, n.readAt = CURRENT_TIMESTAMP where n.recipientMember.id = :memberId and n.isRead = false")
    void markAllAsReadByMemberId(@Param("memberId") Long memberId);

    @Query("select n from FcmNotification n where n.id = :id and n.recipientMember.id = :memberId")
    Optional<FcmNotification> findByIdAndMemberFcmToken_MemberId(Long id, Long memberId);

    @Query("select count(n) from FcmNotification n where n.isRead = false and n.recipientMember.id = :memberId")
    long countByMemberFcmToken_MemberIdAndIsReadFalse(Long memberId);

    @Query("select count(n) from FcmNotification n where n.isRead = false and n.fcmCategory = :fcmCategory and n.recipientMember.id = :memberId")
    long countByMemberFcmToken_MemberIdAndFcmCategoryAndIsReadFalse(Long memberId, FcmCategory fcmCategory);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
        AND (:cursor IS NULL OR n.id < :cursor)
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findNotificationsWithCursor(@Param("memberId") Long memberId,
                                                      @Param("cursor") Long cursor,
                                                      Pageable pageable);

    @Query("""
        SELECT n FROM FcmNotification n
        WHERE n.recipientMember.id = :memberId
        AND n.fcmCategory = :category
        AND (:cursor IS NULL OR n.id < :cursor)
        ORDER BY n.id DESC
        """)
    List<FcmNotification> findNotificationsByCategoryWithCursor(@Param("memberId") Long memberId,
                                                               @Param("category") FcmCategory category,
                                                               @Param("cursor") Long cursor,
                                                               Pageable pageable);
}
