package com.widyu.auth.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.widyu.auth.repository.RefreshTokenRepository;
import com.widyu.global.security.MemberSessionService;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LogoutService 단위 테스트")
class LogoutServiceTest {

    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private MemberUtil memberUtil;
    @Mock private MemberSessionService memberSessionService;

    @InjectMocks
    private LogoutService logoutService;

    @Test
    @DisplayName("로그아웃 시 현재 회원의 리프레시 토큰이 삭제된다")
    void 로그아웃_시_리프레시_토큰이_삭제된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(1L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        // when
        logoutService.logout();

        // then
        verify(memberSessionService).revoke(1L);
    }

    @Test
    @DisplayName("리프레시 토큰이 없는 경우에도 로그아웃이 정상적으로 완료된다")
    void 리프레시_토큰이_없어도_로그아웃이_정상_완료된다() {
        // given
        Member currentMember = mock(Member.class);
        given(currentMember.getId()).willReturn(99L);
        given(memberUtil.getCurrentMember()).willReturn(currentMember);

        // when
        logoutService.logout();

        // then
        verify(memberSessionService).revoke(99L);
    }
}
