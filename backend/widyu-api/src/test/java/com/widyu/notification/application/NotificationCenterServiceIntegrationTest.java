package com.widyu.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.notification.dto.response.NotificationCenterResponse;
import com.widyu.notification.dto.response.NotificationEnvelope;
import com.widyu.notification.dto.response.NotificationReadResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import({JpaAuditingConfig.class, NotificationCenterService.class})
class NotificationCenterServiceIntegrationTest {
    @Autowired private NotificationCenterService service;
    @Autowired private FcmNotificationRepository notifications;
    @Autowired private MemberRepository members;
    @MockBean private MemberUtil memberUtil;
    @MockBean private JPAQueryFactory queryFactory;

    @Test
    @DisplayName("시니어가 위치 필터를 요청하면 FCM_4002 예외가 발생한다")
    void 시니어가_위치_필터를_요청하면_예외가_발생한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        // when / then
        assertThatThrownBy(() -> service.list("LOCATION", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NOTIFICATION_CENTER_FILTER);
        assertThatThrownBy(() -> service.list("OTHER", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NOTIFICATION_CENTER_FILTER);
    }

    @Test
    @DisplayName("보호자가 위치 필터를 조회하면 안전 타입과 레거시 위치만 반환한다")
    void 보호자가_위치_필터를_조회하면_위치_행만_반환한다() {
        // given
        Member guardian = member(MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(guardian);
        FcmNotification safety = notification(guardian, NotificationType.HEART_RATE_EMERGENCY,
                FcmCategory.HEART_MESSAGE, null);
        FcmNotification legacy = notification(guardian, null, FcmCategory.SAFE_ZONE, null);
        notification(guardian, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, null);
        FcmNotification legacyMessage = notification(guardian, null, FcmCategory.HEART_MESSAGE, null);

        // when
        NotificationCenterResponse response = service.list("LOCATION", null);
        NotificationCenterResponse messageResponse = service.list("MESSAGE", null);

        // then
        assertThat(response.items()).extracting(NotificationEnvelope::notificationId)
                .containsExactly(legacy.getId(), safety.getId());
        assertThat(response.unreadCounts()).containsEntry("LOCATION", 2).containsEntry("UNREAD", 4);
        assertThat(messageResponse.items()).extracting(NotificationEnvelope::notificationId)
                .containsExactly(legacyMessage.getId());
        assertThat(response.items().getFirst().eventId()).isEqualTo("legacy:" + legacy.getId());
        assertThat(response.items().getFirst().type()).isEqualTo("LEGACY");
        assertThat(response.items().getFirst().category()).isEqualTo("LOCATION");
        assertThat(response.items().getFirst().priority()).isEqualTo("interaction");
        assertThat(response.items().getFirst().foregroundPresentation()).isEqualTo("BANNER");
        assertThat(response.items().getFirst().expiresAt()).isNull();
        assertThat(response.items().getFirst().deepLink()).isNull();
        assertThat(response.items().getFirst().centerStored()).isTrue();
        assertThat(legacy.getEventId()).isNull();
        assertThat(legacy.getType()).isNull();
    }

    @Test
    @DisplayName("미매핑 레거시 알림을 조회하면 카테고리를 null로 반환한다")
    void 미매핑_레거시_알림을_조회하면_카테고리를_null로_반환한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        FcmNotification unmapped = notification(senior, null, FcmCategory.INCIDENT_SELF_CHECK, null);

        // when
        NotificationCenterResponse response = service.list("ALL", null);

        // then
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().notificationId()).isEqualTo(unmapped.getId());
        assertThat(response.items().getFirst().category()).isNull();
        assertThat(response.items().getFirst().priority()).isEqualTo("interaction");
    }

    @Test
    @DisplayName("만료된 행을 조회하면 목록과 안 읽은 수에서 제외한다")
    void 만료된_행을_조회하면_목록과_안읽은_수에서_제외한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        LocalDateTime now = LocalDateTime.now();
        notification(senior, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, now.minusDays(1));
        FcmNotification active = notification(senior, NotificationType.ALBUM_CREATED,
                FcmCategory.ALBUM, now.plusDays(1));
        FcmNotification legacy = notification(senior, null, FcmCategory.ALBUM, null);

        // when
        NotificationCenterResponse response = service.list("ALBUM", null);

        // then
        assertThat(response.items()).extracting(NotificationEnvelope::notificationId)
                .containsExactly(legacy.getId(), active.getId());
        assertThat(response.unreadCounts()).containsEntry("UNREAD", 2).containsEntry("ALBUM", 2);
        assertThat(response.snapshotRevision()).isNotBlank();
        assertThat(response.serverTime()).isNotNull();

        // 경계값 자체도 제외한다.
        FcmNotification atBoundary = notification(senior, null, FcmCategory.ALBUM, now);
        assertThat(notifications.countCenterUnreadByCategory(senior.getId(), now,
                List.of(NotificationType.ALBUM_CREATED), List.of(FcmCategory.ALBUM))).isEqualTo(2);
        assertThat(notifications.findCenterPageByCategory(senior.getId(), now, null,
                List.of(NotificationType.ALBUM_CREATED), List.of(FcmCategory.ALBUM), PageRequest.of(0, 10)))
                .extracting(FcmNotification::getId).doesNotContain(atBoundary.getId());
    }

    @Test
    @DisplayName("포인트 알림을 조회하면 전체와 안 읽음에만 포함한다")
    void 포인트_알림을_조회하면_전체와_안읽음에만_포함한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        FcmNotification earned = notification(senior, NotificationType.POINT_EARNED, FcmCategory.TARGET, null);
        FcmNotification used = notification(senior, NotificationType.POINT_USED, FcmCategory.TARGET, null);
        FcmNotification goal = notification(senior, NotificationType.GOAL_ACHIEVED, FcmCategory.TARGET, null);

        // when
        NotificationCenterResponse all = service.list("ALL", null);
        NotificationCenterResponse unread = service.list("UNREAD", null);
        NotificationCenterResponse goals = service.list("GOAL", null);

        // then
        assertThat(all.items()).extracting(NotificationEnvelope::notificationId)
                .containsExactly(goal.getId(), used.getId(), earned.getId());
        assertThat(unread.items()).hasSize(3);
        assertThat(goals.items()).extracting(NotificationEnvelope::notificationId)
                .containsExactly(goal.getId());
        assertThat(goals.unreadCounts()).containsEntry("GOAL", 1).containsEntry("UNREAD", 3);
        assertThat(all.items().get(1).category()).isNull();
        assertThat(NotificationType.POINT_EARNED.centerFilter()).isNull();
        assertThat(NotificationType.POINT_USED.centerFilter()).isNull();
    }

    @Test
    @DisplayName("푸시 자격이 없는 타입 행을 조회하면 센터 정책 필드를 그대로 반환한다")
    void 푸시_자격이_없는_타입_행을_조회하면_센터_정책을_반환한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        FcmNotification stored = notifications.saveAndFlush(FcmNotification.builder()
                .recipientMember(senior)
                .eventId(UUID.randomUUID().toString())
                .type(NotificationType.ALBUM_CREATED)
                .fcmCategory(FcmCategory.ALBUM)
                .title("저장된 제목")
                .body("저장된 본문")
                .isRead(false)
                .deepLink("widyu://albums/99")
                .entityId("99")
                .retentionPolicyVersion("v1")
                .pushEligible(false)
                .expiresAt(LocalDateTime.now().plusDays(90))
                .build());

        // when
        NotificationEnvelope envelope = service.list("ALBUM", null).items().getFirst();

        // then
        assertThat(envelope.notificationId()).isEqualTo(stored.getId());
        assertThat(envelope.eventId()).isEqualTo(stored.getEventId());
        assertThat(envelope.category()).isEqualTo("ALBUM");
        assertThat(envelope.priority()).isEqualTo("interaction");
        assertThat(envelope.title()).isEqualTo("저장된 제목");
        assertThat(envelope.body()).isEqualTo("저장된 본문");
        assertThat(envelope.deepLink()).isEqualTo("widyu://albums/99");
        assertThat(envelope.retentionClass()).isEqualTo("ROUTINE_90D");
        assertThat(envelope.foregroundPresentation()).isEqualTo("BANNER");
        assertThat(envelope.centerStored()).isTrue();
        assertThat(envelope.pushEligible()).isFalse();
    }

    @Test
    @DisplayName("커서로 다음 페이지를 조회하면 중복 없이 본인 행만 반환한다")
    void 커서로_다음_페이지를_조회하면_중복없이_본인_행만_반환한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        Member other = member(MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ids.add(notification(senior, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, null).getId());
        }
        FcmNotification otherRow = notification(other, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, null);

        // when
        NotificationCenterResponse first = service.list("ALL", null);
        NotificationCenterResponse second = service.list("ALL", first.nextCursor());
        NotificationCenterResponse withOtherCursor = service.list("ALL", String.valueOf(otherRow.getId()));

        // then
        assertThat(first.items()).hasSize(10);
        assertThat(second.items()).hasSize(2);
        assertThat(second.nextCursor()).isNull();
        List<Long> received = new ArrayList<>();
        received.addAll(first.items().stream().map(NotificationEnvelope::notificationId).toList());
        received.addAll(second.items().stream().map(NotificationEnvelope::notificationId).toList());
        assertThat(received).doesNotHaveDuplicates().containsExactlyElementsOf(ids.reversed());
        assertThat(first.unreadCounts()).containsEntry("UNREAD", 12);
        assertThat(second.unreadCounts()).containsEntry("UNREAD", 12);
        assertThat(withOtherCursor.items()).extracting(NotificationEnvelope::notificationId)
                .doesNotContain(otherRow.getId());
    }

    @Test
    @DisplayName("잘못된 커서를 조회하면 FCM_4003 예외가 발생한다")
    void 잘못된_커서를_조회하면_예외가_발생한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(senior);

        // when / then
        for (String cursor : List.of("text", "0", "-1", "9223372036854775808")) {
            assertThatThrownBy(() -> service.list("ALL", cursor))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_NOTIFICATION_CURSOR);
        }
    }

    @Test
    @DisplayName("알림을 다시 읽으면 최초 읽음 시각을 유지한다")
    void 알림을_다시_읽으면_최초_읽음_시각을_유지한다() {
        // given
        Member senior = member(MemberType.SENIOR);
        Member other = member(MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(senior);
        FcmNotification own = notification(senior, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, null);
        FcmNotification otherRow = notification(other, NotificationType.ALBUM_CREATED, FcmCategory.ALBUM, null);

        // when
        NotificationReadResponse first = service.markAsRead(own.getId());
        NotificationReadResponse repeated = service.markAsRead(own.getId());

        // then
        assertThat(first.notificationId()).isEqualTo(own.getId());
        assertThat(first.readAt()).isNotNull().isEqualTo(repeated.readAt());
        assertThat(notifications.findById(own.getId()).orElseThrow().isRead()).isTrue();
        assertThat(service.list("UNREAD", null).items()).hasSize(0);
        assertThatThrownBy(() -> service.markAsRead(otherRow.getId()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FCM_NOTIFICATION_NOT_FOUND);
        assertThatThrownBy(() -> service.markAsRead(Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FCM_NOTIFICATION_NOT_FOUND);
    }

    private Member member(MemberType type) {
        return members.saveAndFlush(Member.createMember(type, "회원", "01012345678"));
    }

    private FcmNotification notification(Member member, NotificationType type,
                                         FcmCategory category, LocalDateTime expiresAt) {
        String eventId = null;
        if (type != null) {
            eventId = UUID.randomUUID().toString();
        }
        return notifications.saveAndFlush(FcmNotification.builder()
                .recipientMember(member)
                .eventId(eventId)
                .type(type)
                .fcmCategory(category)
                .title("알림 제목")
                .body("알림 본문")
                .isRead(false)
                .expiresAt(expiresAt)
                .build());
    }
}
