package com.widyu.admin.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthService 예외 처리 단위 테스트")
class AdminAuthServiceTest {

    @Mock private LocalAccountRepository localAccountRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AdminAuditLogRepository adminAuditLogRepository;
    @Mock private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @Mock private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;

    @InjectMocks
    private AdminAuthService adminAuthService;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("이메일이 null 또는 공백이면 조회와 비밀번호 검증 전에 거부한다")
    void 빈_이메일은_비밀번호_검증_전에_거부한다(String email) {
        // given / when / then
        assertThatThrownBy(() -> adminAuthService.login(email, "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_EMAIL);
        verifyNoInteractions(localAccountRepository, passwordEncoder, authLimitStore,
                clientIpResolver, jwtTokenProvider, adminAuditLogRepository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("비밀번호가 null 또는 공백이면 조회와 비밀번호 검증 전에 거부한다")
    void 빈_비밀번호는_비밀번호_검증_전에_거부한다(String password) {
        // given / when / then
        assertThatThrownBy(() -> adminAuthService.login("admin@test.com", password))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
        verifyNoInteractions(localAccountRepository, passwordEncoder, authLimitStore,
                clientIpResolver, jwtTokenProvider, adminAuditLogRepository);
    }

    @Test
    @DisplayName("이메일 255자 또는 비밀번호 257자를 입력하면 비밀번호 검증 전에 거부한다")
    void 입력_최대길이를_넘으면_검증_전에_거부한다() {
        // given / when / then
        assertThatThrownBy(() -> adminAuthService.login("a".repeat(255), "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_EMAIL);
        assertThatThrownBy(() -> adminAuthService.login("admin@test.com", "p".repeat(257)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
        verifyNoInteractions(localAccountRepository, passwordEncoder, authLimitStore,
                clientIpResolver, jwtTokenProvider, adminAuditLogRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 254})
    @DisplayName("LocalLogin 허용 길이 경계의 입력이면 비밀번호를 검증하고 실패 예약을 완료한다")
    void 허용_길이_경계는_인증을_진행한다(int emailLength) {
        // given
        String email = "a".repeat(emailLength);
        String password = "p".repeat(emailLength + 2);
        LocalAccount account = LocalAccount.createLocalAccount(member(1L, MemberRole.ADMIN), email, "encoded");
        given(localAccountRepository.findByEmail(email)).willReturn(Optional.of(account));
        // when / then
        assertThatThrownBy(() -> adminAuthService.login(email, password))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
        verify(passwordEncoder).matches(password, "encoded");
        verify(authLimitStore).completeLogin(null, false);
        verifyNoInteractions(jwtTokenProvider, adminAuditLogRepository);
    }

    @Test
    @DisplayName("등록되지 않은 이메일로 로그인하면 INVALID_EMAIL 예외를 던진다")
    void 등록되지_않은_이메일_로그인_시_예외가_발생한다() {
        // given
        given(localAccountRepository.findByEmail("admin@test.com")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> adminAuthService.login("admin@test.com", "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_EMAIL)
                .hasMessageContaining("이메일이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("비밀번호가 다르면 INVALID_PASSWORD 예외를 던진다")
    void 비밀번호가_다르면_예외가_발생한다() {
        // given
        LocalAccount localAccount = LocalAccount.createLocalAccount(
                member(1L, MemberRole.ADMIN), "admin@test.com", "encoded");
        given(localAccountRepository.findByEmail("admin@test.com")).willReturn(Optional.of(localAccount));
        given(passwordEncoder.matches("wrong", "encoded")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> adminAuthService.login("admin@test.com", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD)
                .hasMessageContaining("비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("관리자 권한이 아니면 FORBIDDEN 예외를 던진다")
    void 관리자_권한이_아니면_예외가_발생한다() {
        // given
        LocalAccount localAccount = LocalAccount.createLocalAccount(
                member(1L, MemberRole.USER), "user@test.com", "encoded");
        given(localAccountRepository.findByEmail("user@test.com")).willReturn(Optional.of(localAccount));
        given(passwordEncoder.matches("password", "encoded")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> adminAuthService.login("user@test.com", "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("접근 권한이 없습니다.");
    }

    @Test
    @DisplayName("리프레시 토큰이 비어 있으면 UNAUTHORIZED 예외를 던진다")
    void 리프레시_토큰이_비어_있으면_예외가_발생한다() {
        assertThatThrownBy(() -> adminAuthService.refresh(" "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED)
                .hasMessageContaining("리프레시 토큰이 없습니다.");
    }

    private Member member(Long id, MemberRole role) {
        Member member = Member.createMember(MemberType.GUARDIAN, "관리자", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "role", role);
        return member;
    }
}
