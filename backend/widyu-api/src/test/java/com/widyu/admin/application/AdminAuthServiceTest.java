package com.widyu.admin.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.auth.dto.RefreshTokenDto;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAuthService 예외 처리 단위 테스트")
class AdminAuthServiceTest {

    @Mock private LocalAccountRepository localAccountRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private AdminAuditLogRepository adminAuditLogRepository;
    @Mock private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @Mock private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;

    @Mock private MemberRepository memberRepository;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    @DisplayName("이메일이 null 또는 공백이면 조회와 비밀번호 검증 전에 거부한다")
    void 빈_이메일은_비밀번호_검증_전에_거부한다(String email) {
        // given / when / then
        assertThatThrownBy(() -> service().login(email, "password"))
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
        assertThatThrownBy(() -> service().login("admin@test.com", password))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
        verifyNoInteractions(localAccountRepository, passwordEncoder, authLimitStore,
                clientIpResolver, jwtTokenProvider, adminAuditLogRepository);
    }

    @Test
    @DisplayName("이메일 255자 또는 비밀번호 257자를 입력하면 비밀번호 검증 전에 거부한다")
    void 입력_최대길이를_넘으면_검증_전에_거부한다() {
        // given / when / then
        assertThatThrownBy(() -> service().login("a".repeat(255), "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_EMAIL);
        assertThatThrownBy(() -> service().login("admin@test.com", "p".repeat(257)))
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
        assertThatThrownBy(() -> service().login(email, password))
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
        assertThatThrownBy(() -> service().login("admin@test.com", "password"))
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
        assertThatThrownBy(() -> service().login("admin@test.com", "wrong"))
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
        assertThatThrownBy(() -> service().login("user@test.com", "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("접근 권한이 없습니다.");
    }

    @Test
    @DisplayName("리프레시 토큰이 비어 있으면 UNAUTHORIZED 예외를 던진다")
    void 리프레시_토큰이_비어_있으면_예외가_발생한다() {
        assertThatThrownBy(() -> service().refresh(" "))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED)
                .hasMessageContaining("리프레시 토큰이 없습니다.");
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"INACTIVE", "DELETED", "PROCESSING"})
    @DisplayName("활성 상태가 아닌 관리자가 로그인하면 접근을 거절한다")
    void 활성_상태가_아닌_관리자의_로그인을_거절한다(Status status) {
        // given
        Member member = member(1L, MemberRole.ADMIN);
        ReflectionTestUtils.setField(member, "status", status);
        LocalAccount account = LocalAccount.createLocalAccount(member, "admin@test.com", "encoded");
        given(localAccountRepository.findByEmail("admin@test.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("password", "encoded")).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service().login("admin@test.com", "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(jwtTokenProvider).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = MemberRole.class, names = {"USER", "TEMPORARY"})
    @DisplayName("일반 회원 토큰으로 관리자 재발급을 요청하면 권한 상승을 거절한다")
    void 일반_회원의_관리자_재발급을_거절한다(MemberRole role) {
        // given
        given(jwtTokenProvider.retrieveRefreshToken("user-refresh"))
                .willReturn(new RefreshTokenDto(1L, "user-refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, role)));

        // when & then
        assertThatThrownBy(() -> service().refresh("user-refresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(jwtTokenProvider).should(never()).generateTokenPair(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"INACTIVE", "DELETED", "PROCESSING"})
    @DisplayName("활성 상태가 아닌 관리자의 기존 토큰으로 재발급하면 접근을 거절한다")
    void 활성_상태가_아닌_관리자의_재발급을_거절한다(Status status) {
        // given
        Member member = member(1L, MemberRole.ADMIN);
        ReflectionTestUtils.setField(member, "status", status);
        given(jwtTokenProvider.retrieveRefreshToken("refresh"))
                .willReturn(new RefreshTokenDto(1L, "refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));

        // when & then
        assertThatThrownBy(() -> service().refresh("refresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(jwtTokenProvider).should(never()).generateTokenPair(any(), any(), any());
    }

    @Test
    @DisplayName("삭제된 회원의 토큰으로 재발급하면 접근을 거절한다")
    void 삭제된_회원의_재발급을_거절한다() {
        // given
        given(jwtTokenProvider.retrieveRefreshToken("refresh"))
                .willReturn(new RefreshTokenDto(1L, "refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().refresh("refresh"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        then(jwtTokenProvider).should(never()).generateTokenPair(any(), any(), any());
    }

    @Test
    @DisplayName("활성 관리자가 로그인하면 관리자 토큰을 반환한다")
    void 활성_관리자에게_로그인_토큰을_반환한다() {
        // given
        LocalAccount account = LocalAccount.createLocalAccount(member(1L, MemberRole.ADMIN), "admin@test.com", "encoded");
        given(localAccountRepository.findByEmail("admin@test.com")).willReturn(Optional.of(account));
        given(passwordEncoder.matches("password", "encoded")).willReturn(true);
        TokenPairResponse expected = TokenPairResponse.of(1L, "access", "refresh");
        given(jwtTokenProvider.generateTokenPair(1L, MemberRole.ADMIN, "local")).willReturn(expected);

        // when
        TokenPairResponse actual = service().login("admin@test.com", "password");

        // then
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("활성 관리자가 재발급하면 관리자 토큰을 반환한다")
    void 활성_관리자에게_새_토큰을_반환한다() {
        // given
        given(jwtTokenProvider.retrieveRefreshToken("refresh"))
                .willReturn(new RefreshTokenDto(1L, "refresh", 60L));
        given(memberRepository.findById(1L)).willReturn(Optional.of(member(1L, MemberRole.ADMIN)));
        TokenPairResponse expected = TokenPairResponse.of(1L, "new-access", "new-refresh");
        given(jwtTokenProvider.generateTokenPair(1L, MemberRole.ADMIN, "local")).willReturn(expected);

        // when
        TokenPairResponse actual = service().refresh("refresh");

        // then
        assertThat(actual).isEqualTo(expected);
    }

    private AdminAuthService service() {
        return new AdminAuthService(localAccountRepository, passwordEncoder, jwtTokenProvider,
                adminAuditLogRepository, new AdminAccessValidator(memberRepository), authLimitStore, clientIpResolver);
    }

    private Member member(Long id, MemberRole role) {
        Member member = Member.createMember(MemberType.GUARDIAN, "관리자", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        ReflectionTestUtils.setField(member, "role", role);
        return member;
    }
}
