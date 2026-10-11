package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.request.SendNotificationRequest;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

    @Mock private MemberUtil memberUtil;
    @Mock private MemberRepository memberRepository;
    @Mock private FcmOutboxService outboxService;
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

    private Member member(Long id, MemberType type) {
        Member member = Member.createMember(type, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
