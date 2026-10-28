package com.widyu.fcm.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    void 토큰을_등록하면_현재_회원으로_저장_또는_활성화를_한_번에_요청한다() {
        // given
        Member currentMember = member(1L, "보호자");
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        // when
        memberFcmTokenService.saveOrActivateFcmToken("fcm-token");

        // then
        then(memberFcmTokenRepository).should().upsertActive(eq("fcm-token"), eq(1L), any(LocalDateTime.class));
        then(memberFcmTokenRepository).should(never()).findByToken(any());
        then(memberFcmTokenRepository).should(never()).save(any());
    }

    private Member member(Long id, String name) {
        Member member = Member.createMember(MemberType.GUARDIAN, name, "01012341234");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }
}
