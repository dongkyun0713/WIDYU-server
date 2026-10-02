package com.widyu.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.notification.dto.response.NotificationReadResponse;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceTest {
    @Mock private FcmNotificationRepository notifications;
    @Mock private MemberUtil memberUtil;
    @Mock private Member member;
    @InjectMocks private NotificationCenterService service;

    @Test
    @DisplayName("알림을 읽으면 소유 확인 후 조건부 갱신하고 다시 조회한 시각을 반환한다")
    void 알림을_읽으면_조건부_갱신후_재조회한다() {
        // given
        Long notificationId = 17L;
        Long memberId = 3L;
        LocalDateTime firstReadAt = LocalDateTime.of(2026, 10, 2, 9, 30);
        FcmNotification unread = FcmNotification.builder().id(notificationId).isRead(false).build();
        FcmNotification read = FcmNotification.builder().id(notificationId).isRead(true)
                .readAt(firstReadAt).build();
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(memberId);
        given(notifications.findByIdAndRecipientMemberId(notificationId, memberId))
                .willReturn(Optional.of(unread), Optional.of(read));
        given(notifications.markAsReadIfUnread(eq(notificationId), eq(memberId),
                any(LocalDateTime.class))).willReturn(1);

        // when
        NotificationReadResponse response = service.markAsRead(notificationId);

        // then
        assertThat(response.notificationId()).isEqualTo(notificationId);
        assertThat(response.readAt()).isEqualTo(firstReadAt);
        InOrder order = inOrder(notifications);
        order.verify(notifications).findByIdAndRecipientMemberId(notificationId, memberId);
        order.verify(notifications).markAsReadIfUnread(eq(notificationId), eq(memberId),
                any(LocalDateTime.class));
        order.verify(notifications).findByIdAndRecipientMemberId(notificationId, memberId);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("이미 읽은 알림을 다시 읽으면 조건부 갱신 0건 뒤 최초 시각을 반환한다")
    void 이미_읽은_알림을_다시_읽으면_최초_시각을_반환한다() {
        // given
        Long notificationId = 17L;
        Long memberId = 3L;
        LocalDateTime firstReadAt = LocalDateTime.of(2026, 10, 2, 9, 30);
        FcmNotification read = FcmNotification.builder().id(notificationId).isRead(true)
                .readAt(firstReadAt).build();
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(member.getId()).willReturn(memberId);
        given(notifications.findByIdAndRecipientMemberId(notificationId, memberId))
                .willReturn(Optional.of(read));
        given(notifications.markAsReadIfUnread(eq(notificationId), eq(memberId),
                any(LocalDateTime.class))).willReturn(0);

        // when
        NotificationReadResponse response = service.markAsRead(notificationId);

        // then
        assertThat(response.readAt()).isEqualTo(firstReadAt);
        InOrder order = inOrder(notifications);
        order.verify(notifications).findByIdAndRecipientMemberId(notificationId, memberId);
        order.verify(notifications).markAsReadIfUnread(eq(notificationId), eq(memberId),
                any(LocalDateTime.class));
        order.verify(notifications).findByIdAndRecipientMemberId(notificationId, memberId);
        order.verifyNoMoreInteractions();
    }
}
