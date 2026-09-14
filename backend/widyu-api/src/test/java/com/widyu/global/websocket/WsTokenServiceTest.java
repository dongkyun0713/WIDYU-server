package com.widyu.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.widyu.global.error.BusinessException;
import com.widyu.global.security.MemberSessionService;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("WS 일회용 토큰 버전 검증")
class WsTokenServiceTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> values;
    @Mock private MemberUtil memberUtil;
    @Mock private MemberSessionService memberSessionService;
    @InjectMocks private WsTokenService service;

    @Test
    @DisplayName("HTTP 인증 직후 폐기되면 이전 인증으로 새 WS 토큰을 발급하지 않는다")
    void 인증과_WS_발급_사이_폐기를_우회하지_못한다() {
        // given
        var member = Member.createMember(MemberType.GUARDIAN, "회원", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        member.revokeSessions();
        given(memberUtil.getCurrentMember()).willReturn(member);
        given(memberSessionService.lock(1L)).willReturn(member);
        var principal = new PrincipalDetails(1L, MemberRole.USER, 0L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        // when / then
        try {
            Assertions.assertThatThrownBy(service::issueToken)
                    .isInstanceOf(BusinessException.class);
            Mockito.verifyNoInteractions(redisTemplate);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1:", "invalid:0", "1:0:0"})
    @DisplayName("구형 또는 손상된 WS 토큰을 소비하면 인증하지 않는다")
    void 버전_없는_WS_토큰을_거절한다(String stored) {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.getAndDelete("ws-token:token")).willReturn(stored);
        // when / then
        assertThat(service.consumeIdentity("token")).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("버전과 ACTIVE 상태가 현재 DB와 일치할 때만 WS 인증 정보를 반환한다")
    void 현재_DB_검증을_통과해야_WS_인증된다(boolean current) {
        // given
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.getAndDelete("ws-token:token")).willReturn("1:7");
        given(memberSessionService.isCurrent(1L, 7L)).willReturn(current);
        // when
        var identity = service.consumeIdentity("token");
        // then
        if (current) {
            assertThat(identity).isEqualTo(new WsTokenService.WsSessionIdentity(1L, 7L));
        } else {
            assertThat(identity).isNull();
        }
    }
}
