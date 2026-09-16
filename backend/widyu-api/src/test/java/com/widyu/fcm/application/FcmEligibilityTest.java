package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmOutbox;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class FcmEligibilityTest {
    @Mock private MemberRepository members;
    @Mock private FamilyMembershipRepository memberships;
    @Mock private SeniorProfileRepository seniors;
    @Mock private NotificationSettingService settings;
    @InjectMocks private FcmEligibility eligibility;

    @Test
    @DisplayName("자기 알림 수신자가 활성 상태면 발송을 허용한다")
    void 자기알림의_활성_수신자는_허용한다() {
        // given
        FcmOutbox outbox = outbox(null);
        activeRecipient(outbox);
        enabled();

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(true);
    }

    @Test
    @DisplayName("접수 당시 가족 관계가 유지되면 발송을 허용한다")
    void 같은_가족의_활성_수신자는_허용한다() {
        // given
        FcmOutbox outbox = outbox(2L);
        activeRecipient(outbox);
        enabled();
        given(members.findById(2L)).willReturn(Optional.of(member(2L, Status.ACTIVE)));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(seniors.findFamilyIdByMemberId(2L)).willReturn(Optional.of(10L));

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(true);
    }

    @Test
    @DisplayName("수신자가 정지되면 발송을 차단한다")
    void 정지된_수신자는_차단한다() {
        // given
        FcmOutbox outbox = outbox(null);
        given(members.findById(1L)).willReturn(Optional.of(member(1L, Status.INACTIVE)));

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("토큰이 비활성화되면 발송을 차단한다")
    void 비활성_토큰은_차단한다() {
        // given
        FcmOutbox outbox = outbox(null);
        activeRecipient(outbox);
        outbox.getMemberFcmToken().deactivate();

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("토큰 소유자가 변경되면 과거 수신자 발송을 차단한다")
    void 소유자가_변경된_토큰은_차단한다() {
        // given
        FcmOutbox outbox = outbox(null);
        activeRecipient(outbox);
        outbox.getMemberFcmToken().transferTo(member(3L, Status.ACTIVE));

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("수신자가 알림 설정을 끄면 발송을 차단한다")
    void 설정을_끄면_차단한다() {
        // given
        FcmOutbox outbox = outbox(null);
        activeRecipient(outbox);
        given(settings.isNotificationEnabled(1L, FcmCategory.ALBUM)).willReturn(false);

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("관련 회원이 정지되면 가족 알림을 차단한다")
    void 관련_회원이_정지되면_차단한다() {
        // given
        FcmOutbox outbox = outbox(2L);
        activeRecipient(outbox);
        enabled();
        given(members.findById(2L)).willReturn(Optional.of(member(2L, Status.INACTIVE)));

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("수신자가 가족을 탈퇴하면 발송을 차단한다")
    void 가족을_탈퇴하면_차단한다() {
        // given
        FcmOutbox outbox = outbox(2L);
        activeRecipient(outbox);
        enabled();
        given(members.findById(2L)).willReturn(Optional.of(member(2L, Status.ACTIVE)));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.empty());
        given(memberships.findFamilyIdByGuardianId(1L)).willReturn(Optional.empty());

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("수신자가 다른 가족에 재가입하면 기존 요청 발송을 차단한다")
    void 다른_가족에_재가입하면_차단한다() {
        // given
        FcmOutbox outbox = outbox(2L);
        activeRecipient(outbox);
        enabled();
        given(members.findById(2L)).willReturn(Optional.of(member(2L, Status.ACTIVE)));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.of(20L));

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    @Test
    @DisplayName("관련 회원이 가족을 탈퇴하면 발송을 차단한다")
    void 관련_회원이_탈퇴하면_차단한다() {
        // given
        FcmOutbox outbox = outbox(2L);
        activeRecipient(outbox);
        enabled();
        given(members.findById(2L)).willReturn(Optional.of(member(2L, Status.ACTIVE)));
        given(seniors.findFamilyIdByMemberId(1L)).willReturn(Optional.of(10L));
        given(seniors.findFamilyIdByMemberId(2L)).willReturn(Optional.empty());
        given(memberships.findFamilyIdByGuardianId(2L)).willReturn(Optional.empty());

        // when
        boolean allowed = eligibility.eligible(outbox);

        // then
        assertThat(allowed).isEqualTo(false);
    }

    private void activeRecipient(FcmOutbox outbox) {
        given(members.findById(1L)).willReturn(Optional.of(outbox.getRecipientMember()));
    }

    private void enabled() {
        given(settings.isNotificationEnabled(1L, FcmCategory.ALBUM)).willReturn(true);
    }

    private FcmOutbox outbox(Long relatedId) {
        Member recipient = member(1L, Status.ACTIVE);
        MemberFcmToken token = MemberFcmToken.builder()
                .id(11L).member(recipient).token("mock-token").active(true).build();
        return FcmOutbox.builder().recipientMember(recipient).memberFcmToken(token)
                .relatedMemberId(relatedId).familyId(10L).fcmCategory(FcmCategory.ALBUM).build();
    }

    private Member member(Long id, Status status) {
        Member member = Member.createMember(MemberType.SENIOR, "회원", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }
}
