package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

import com.widyu.admin.application.AdminAuthService;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.AuthLimitProperties;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.*;
import com.widyu.member.repository.LocalAccountRepository;
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
class AdminLoginLimitRedisTest {
    @Mock private LocalAccountRepository accounts;
    @Mock private PasswordEncoder encoder;
    @Mock private JwtTokenProvider tokens;
    @Mock private AdminAuditLogRepository audits;
    @Mock private ClientIpResolver ip;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "password", "role"})
    @DisplayName("관리자 인증이 거부되면 실패를 유지하고 다음 요청을 제한한다")
    void 관리자_인증_거부는_실패_예약을_유지한다(String reason) throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = store(fixture, 2, 100);
            var service = service(store);
            ErrorCode error = ErrorCode.INVALID_EMAIL;
            if (!reason.equals("missing")) {
                MemberRole role = MemberRole.ADMIN;
                error = ErrorCode.INVALID_PASSWORD;
                if (reason.equals("role")) {
                    role = MemberRole.USER;
                    error = ErrorCode.FORBIDDEN;
                    given(encoder.matches("password", "encoded")).willReturn(true);
                }
                given(accounts.findByEmail(anyString())).willReturn(Optional.of(account(role)));
            }
            // when / then
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> service.login("admin@example.com", "password"))
                        .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", error);
            }
            assertThatThrownBy(() -> service.login("admin@example.com", "password"))
                    .isInstanceOf(AuthRateLimitException.class);
            verifyNoInteractions(tokens, audits);
        }
    }

    @Test
    @DisplayName("관리자 로그인 성공은 자기 예약만 해제하고 일반 로그인과 계정 한도를 공유한다")
    void 관리자_성공은_기존_실패를_보존하고_계정_한도를_공유한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = store(fixture, 2, 100);
            var service = service(store);
            given(accounts.findByEmail(anyString())).willReturn(Optional.of(account(MemberRole.ADMIN)));
            given(encoder.matches("correct", "encoded")).willReturn(true);
            store.completeLogin(store.reserveLogin("id:7", "192.0.2.1"), false);
            // when
            for (int i = 0; i < 5; i++) {
                service.login("alias@example.com", "correct");
            }
            assertThatThrownBy(() -> service.login("admin@example.com", "wrong"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
            // then
            assertThatThrownBy(() -> store.reserveLogin("id:7", "198.51.100.1"))
                    .isInstanceOf(AuthRateLimitException.class);
            verify(tokens, times(5)).generateTokenPair(any(), eq(MemberRole.ADMIN), eq("local"));
            verify(audits, times(5)).save(any());
        }
    }

    @Test
    @DisplayName("다른 관리자 계정도 같은 IP 한도가 차면 비밀번호 검증 전에 거부한다")
    void 관리자_IP_한도와_Redis_장애는_토큰을_차단한다() throws Exception {
        // given
        try (var fixture = new AuthRedisFixture()) {
            var store = store(fixture, 10, 1);
            var service = service(store);
            store.reserveLogin("other", "192.0.2.1");
            // when / then
            assertThatThrownBy(() -> service.login("admin@example.com", "password"))
                    .isInstanceOf(AuthRateLimitException.class);
            fixture.stopRedis();
            assertThatThrownBy(() -> service.login("admin@example.com", "password"))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE);
            verifyNoInteractions(encoder, tokens, audits);
        }
    }

    private AuthLimitStore store(AuthRedisFixture fixture, int account, int ipLimit) {
        return new AuthLimitStore(fixture.redis, new AuthLimitProperties(60, 3, 10, 30, 1000, 900, account, ipLimit));
    }

    private AdminAuthService service(AuthLimitStore store) {
        given(ip.resolve()).willReturn("192.0.2.1");
        return new AdminAuthService(accounts, encoder, tokens, audits,
                new com.widyu.admin.validator.AdminAccessValidator(
                        mock(com.widyu.member.repository.MemberRepository.class)), store, ip);
    }

    private LocalAccount account(MemberRole role) {
        var member = Member.createMember(MemberType.GUARDIAN, "synthetic", "01000000000");
        ReflectionTestUtils.setField(member, "id", 1L);
        ReflectionTestUtils.setField(member, "role", role);
        var account = LocalAccount.createLocalAccount(member, "admin@example.com", "encoded");
        ReflectionTestUtils.setField(account, "id", 7L);
        return account;
    }
}
