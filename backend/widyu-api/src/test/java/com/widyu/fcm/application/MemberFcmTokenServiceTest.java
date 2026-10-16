package com.widyu.fcm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MemberFcmTokenServiceTest {

    @Mock private MemberFcmTokenRepository memberFcmTokenRepository;
    @Mock private MemberUtil memberUtil;

    @InjectMocks
    private MemberFcmTokenService memberFcmTokenService;

    @Test
    void 다른_회원이_기존_토큰을_등록하면_현재_회원에게_소유권을_이전하고_활성화한다() {
        // given
        Member previousMember = member(1L, "기존 보호자");
        Member currentMember = member(2L, "현재 보호자");
        MemberFcmToken existingToken = inactiveToken(previousMember);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(memberFcmTokenRepository.findByToken("fcm-token")).willReturn(Optional.of(existingToken));

        // when
        memberFcmTokenService.saveOrActivateFcmToken("fcm-token");

        // then
        assertThat(existingToken.getMember()).isEqualTo(currentMember);
        assertThat(existingToken.isActive()).isTrue();
        assertThat(existingToken.getExpiredAt()).isNull();
        assertThat(existingToken.getLastUsedAt()).isNotNull();
        then(memberFcmTokenRepository).should(never()).save(any());
    }

    @Test
    void 현재_회원이_비활성_토큰을_다시_등록하면_활성화한다() {
        // given
        Member currentMember = member(1L, "보호자");
        MemberFcmToken existingToken = inactiveToken(currentMember);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(memberFcmTokenRepository.findByToken("fcm-token")).willReturn(Optional.of(existingToken));

        // when
        memberFcmTokenService.saveOrActivateFcmToken("fcm-token");

        // then
        assertThat(existingToken.getMember()).isEqualTo(currentMember);
        assertThat(existingToken.isActive()).isTrue();
        assertThat(existingToken.getExpiredAt()).isNull();
        assertThat(existingToken.getLastUsedAt()).isNotNull();
        then(memberFcmTokenRepository).should(never()).save(any());
    }

    @Test
    void 새_토큰을_등록하면_현재_회원의_활성_토큰으로_저장한다() {
        // given
        Member currentMember = member(1L, "보호자");
        given(memberUtil.getCurrentMember()).willReturn(currentMember);
        given(memberFcmTokenRepository.findByToken("new-fcm-token")).willReturn(Optional.empty());
        ArgumentCaptor<MemberFcmToken> tokenCaptor = ArgumentCaptor.forClass(MemberFcmToken.class);

        // when
        memberFcmTokenService.saveOrActivateFcmToken("new-fcm-token");

        // then
        then(memberFcmTokenRepository).should().save(tokenCaptor.capture());
        MemberFcmToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getMember()).isEqualTo(currentMember);
        assertThat(savedToken.getToken()).isEqualTo("new-fcm-token");
        assertThat(savedToken.isActive()).isTrue();
        assertThat(savedToken.getRegisteredAt()).isNotNull();
    }

    private Member member(Long id, String name) {
        Member member = Member.createMember(MemberType.GUARDIAN, name, "01012341234");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private MemberFcmToken inactiveToken(Member member) {
        return MemberFcmToken.builder()
                .member(member)
                .token("fcm-token")
                .registeredAt(LocalDateTime.now().minusDays(1))
                .active(false)
                .expiredAt(LocalDateTime.now().minusHours(1))
                .build();
    }
}
