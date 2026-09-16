package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AuthLimitProperties;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LocalLoginLimitRedisTest {
    @Mock private PasswordEncoder encoder;
    @Mock private MemberRepository members;
    @Mock private LocalAccountRepository accounts;
    @Mock private JwtTokenProvider tokens;
    @Mock private TemporaryMemberUtil temporary;
    @Mock private ClientIpResolver ip;

    private LocalLoginService service(AuthRedisFixture fixture) {
        given(ip.resolve()).willReturn("192.0.2.1");
        var store = new AuthLimitStore(fixture.redis, new AuthLimitProperties(60, 3, 10, 30, 1000, 900, 10, 100));
        return new LocalLoginService(encoder, members, accounts, tokens, temporary, store, ip);
    }

    private LocalAccount account() {
        var member = Member.createMember(MemberType.GUARDIAN, "synthetic", "01000000000");
        ReflectionTestUtils.setField(member, "id", 1L);
        var account = LocalAccount.createLocalAccount(member, "user@example.com", "encoded");
        ReflectionTestUtils.setField(account, "id", 7L);
        return account;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("없는 계정과 오비밀번호가 열 번 발생하면 다음 요청은 비밀번호 검증 전에 차단한다")
    void 없는_계정과_오비밀번호를_동일하게_제한한다(boolean exists) throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var service = service(fixture);
            Optional<LocalAccount> account = Optional.empty();
            if (exists) {
                account = Optional.of(account());
            }
            given(accounts.findByEmail("user@example.com")).willReturn(account);
            var request = new LocalGuardianSignInRequest("user@example.com", "wrong");
            // when / then
            for (int i = 0; i < 10; i++) {
                assertThatThrownBy(() -> service.signIn(request)).isInstanceOf(BusinessException.class)
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
            }
            assertThatThrownBy(() -> service.signIn(request)).isInstanceOf(AuthRateLimitException.class);
            verify(encoder, times(10)).matches(eq("wrong"), anyString());
            verifyNoInteractions(tokens);
        }
    }

    @Test
    @DisplayName("정상 로그인을 반복하면 슬롯을 해제하고 같은 DB 계정의 이메일 별칭은 실패 한도를 공유한다")
    void 정상_로그인은_슬롯을_해제하고_동일_계정_별칭은_한도를_공유한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var service = service(fixture);
            var account = account();
            given(accounts.findByEmail(anyString())).willReturn(Optional.of(account));
            given(encoder.matches("correct", "encoded")).willReturn(true);
            var expected = TokenPairResponse.of(1L, "synthetic-access", "synthetic-refresh");
            given(tokens.generateTokenPair(any(), any(), anyString())).willReturn(expected);
            // when / then
            for (int i = 0; i < 15; i++) {
                assertThat(service.signIn(new LocalGuardianSignInRequest("user@example.com", "correct")))
                        .isEqualTo(expected);
            }
            for (int i = 0; i < 10; i++) {
                String alias = "alias" + i + "@example.com";
                assertThatThrownBy(() -> service.signIn(new LocalGuardianSignInRequest(alias, "wrong")))
                        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
            }
            assertThatThrownBy(() -> service.signIn(new LocalGuardianSignInRequest("user@example.com", "correct")))
                    .isInstanceOf(AuthRateLimitException.class);
            verify(tokens, times(15)).generateTokenPair(any(), any(), anyString());
        }
    }
}
