package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.request.SendNotificationRequest;
import com.widyu.fcm.dto.response.FcmCategoryResponse;
import com.widyu.fcm.dto.response.FcmNotificationResponse;
import com.widyu.fcm.dto.response.FcmNotificationResponses;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private FcmOutboxService outboxService;
    @Mock private FcmNotificationRepository fcmNotificationRepository;
    @InjectMocks private FcmService service;

    @Test
    @DisplayName("보호자에게 응원 메시지를 보내면 알림센터 경로를 전달한다")
    void 보호자에게_응원_메시지를_보내면_알림센터_경로를_전달한다() {
        // given
        Member sender = member(1L, MemberType.SENIOR);
        Member receiver = member(2L, MemberType.GUARDIAN);
        given(memberUtil.getCurrentMember()).willReturn(sender);
        given(memberRepository.findById(2L)).willReturn(Optional.of(receiver));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        service.sendNotificationToMember(new SendNotificationRequest(2L, "힘내세요"));

        // then
        then(outboxService).should().enqueue(eq(2L), captor.capture());
        assertThat(captor.getValue().notificationType()).isNull();
        assertThat(captor.getValue().dataForEnqueue("cheer-guardian"))
                .containsEntry("deepLink", "/notification");
    }

    @Test
    @DisplayName("시니어에게 응원 메시지를 보내면 알림센터 경로와 응원 타입을 전달한다")
    void 시니어에게_응원_메시지를_보내면_알림센터_경로와_응원_타입을_전달한다() {
        // given
        Member sender = member(1L, MemberType.GUARDIAN);
        Member receiver = member(2L, MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(sender);
        given(memberRepository.findById(2L)).willReturn(Optional.of(receiver));
        ArgumentCaptor<FcmSendDto> captor = ArgumentCaptor.forClass(FcmSendDto.class);

        // when
        service.sendNotificationToMember(new SendNotificationRequest(2L, "힘내세요"));

        // then
        then(outboxService).should().enqueue(eq(2L), captor.capture());
        assertThat(captor.getValue().dataForEnqueue("cheer-senior"))
                .isEqualTo(Map.of("deepLink", "/notification", "type", "CHEER_MESSAGE_RECEIVED"));
    }

    @Test
    @DisplayName("알림 카테고리를 조회하면 전체 바로 뒤에 전체 안 읽은 수를 담은 안 읽음 항목을 반환한다")
    void 알림_카테고리를_조회하면_전체_뒤에_안_읽음_항목을_반환한다() {
        // given
        Member member = member(1L, MemberType.SENIOR);
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(fcmNotificationRepository.countByMemberFcmToken_MemberIdAndIsReadFalse(1L)).willReturn(7L);
        given(fcmNotificationRepository.countByMemberFcmToken_MemberIdAndFcmCategoryAndIsReadFalse(1L, FcmCategory.ALBUM))
                .willReturn(3L);

        // when
        List<FcmCategoryResponse> categories = service.getNotificationCategories();

        // then
        assertThat(categories).hasSize(FcmCategory.values().length);
        assertThat(categories.get(0).getLabel()).isEqualTo("ALL");
        assertThat(categories.get(1).getLabel()).isEqualTo("UNREAD");
        assertThat(categories.get(1).getName()).isEqualTo("안 읽음");
        assertThat(categories.get(1).getCount()).isEqualTo(7L);
        assertThat(categories.get(2).getLabel()).isEqualTo("ALBUM");
        assertThat(categories.get(2).getCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("알림 목록을 안 읽음으로 조회하면 안 읽은 알림만 조회한다")
    void 알림_목록을_안_읽음으로_조회하면_안_읽은_알림만_조회한다() {
        // given
        Member member = member(1L, MemberType.SENIOR);
        FcmNotification unread = FcmNotification.builder().id(10L).recipientMember(member)
                .fcmCategory(FcmCategory.ALBUM).title("새 게시물").isRead(false).build();
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(fcmNotificationRepository.findUnreadNotificationsWithCursor(eq(1L), isNull(), any(Pageable.class)))
                .willReturn(List.of(unread));

        // when
        FcmNotificationResponses responses = service.getNotificationsForCurrentUser("UNREAD", null);

        // then
        assertThat(responses.notifications()).extracting(FcmNotificationResponse::notificationId)
                .containsExactly(10L);
        then(fcmNotificationRepository).should(never())
                .findNotificationsWithCursor(anyLong(), any(), any(Pageable.class));
    }

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
