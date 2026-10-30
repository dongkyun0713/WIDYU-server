package com.widyu.auth.application.guardian.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.infrastructure.ClientIpResolver;
import com.widyu.auth.dto.request.ChangePasswordRequest;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.LocalSignupResponse;
import com.widyu.auth.dto.response.MemberInfoResponse;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.util.TemporaryMemberUtil;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocalLoginService 단위 테스트")
class LocalLoginServiceTest {

    @Mock private PasswordEncoder passwordEncoder;
    @Mock private MemberRepository memberRepository;
    @Mock private LocalAccountRepository localAccountRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private TemporaryMemberUtil temporaryMemberUtil;

    @Mock private AuthLimitStore authLimitStore;
    @Mock private ClientIpResolver clientIpResolver;

    @InjectMocks
    private LocalLoginService localLoginService;

    @Test
    @DisplayName("신규 이메일로 회원가입 시 새 멤버를 생성하고 토큰을 반환한다")
    void 신규_이메일로_회원가입_시_새_멤버를_생성하고_토큰을_반환한다() {
        // given
        TemporaryMember temp = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        String email = "test@test.com";
        String rawPassword = "password123!";
        Member newMember = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(newMember, "id", 1L);
        TokenPairResponse tokenPair = TokenPairResponse.of(1L, "access-token", "refresh-token");

        given(localAccountRepository.existsByEmail(email)).willReturn(false);
        given(memberRepository.findByPhoneNumberAndName("01012345678", "홍길동")).willReturn(Optional.empty());
        given(memberRepository.save(any(Member.class))).willReturn(newMember);
        given(passwordEncoder.encode(rawPassword)).willReturn("encoded-password");
        given(localAccountRepository.save(any(LocalAccount.class))).willReturn(
                LocalAccount.createLocalAccount(newMember, email, "encoded-password")
        );
        given(jwtTokenProvider.generateTokenPair(any(), any(), any())).willReturn(tokenPair);

        // when
        LocalSignupResponse result = localLoginService.signupGuardianWithLocal(temp, email, rawPassword);

        // then
        assertThat(result).isNotNull();
        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(memberRepository).save(any(Member.class));
        verify(localAccountRepository).save(any(LocalAccount.class));
    }

    @Test
    @DisplayName("기존 전화번호+이름의 멤버가 있으면 새 멤버를 생성하지 않고 재사용한다")
    void 기존_전화번호_이름의_멤버가_있으면_기존_멤버를_재사용한다() {
        // given
        TemporaryMember temp = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        String email = "new@test.com";
        Member existingMember = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(existingMember, "id", 1L);
        TokenPairResponse tokenPair = TokenPairResponse.of(1L, "access", "refresh");

        given(localAccountRepository.existsByEmail(email)).willReturn(false);
        given(memberRepository.findByPhoneNumberAndName("01012345678", "홍길동"))
                .willReturn(Optional.of(existingMember));
        given(passwordEncoder.encode(any())).willReturn("encoded");
        given(localAccountRepository.save(any())).willReturn(
                LocalAccount.createLocalAccount(existingMember, email, "encoded")
        );
        given(jwtTokenProvider.generateTokenPair(any(), any(), any())).willReturn(tokenPair);

        // when
        localLoginService.signupGuardianWithLocal(temp, email, "password");

        // then
        verify(memberRepository, never()).save(any(Member.class));
    }

    @Test
    @DisplayName("이미 등록된 이메일로 회원가입 시 BusinessException을 던진다")
    void 중복_이메일로_회원가입_시_예외가_발생한다() {
        // given
        TemporaryMember temp = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        String email = "existing@test.com";
        given(localAccountRepository.existsByEmail(email)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> localLoginService.signupGuardianWithLocal(temp, email, "password"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REGISTERED_EMAIL);
    }

    @Test
    @DisplayName("등록되지 않은 이메일 확인 시 true를 반환한다")
    void 미등록_이메일_확인_시_true를_반환한다() {
        // given
        given(localAccountRepository.existsByEmail("new@test.com")).willReturn(false);

        // when
        boolean result = localLoginService.isEmailRegistered(new EmailCheckRequest("new@test.com"));

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("이미 등록된 이메일 확인 시 false를 반환한다")
    void 등록된_이메일_확인_시_false를_반환한다() {
        // given
        given(localAccountRepository.existsByEmail("existing@test.com")).willReturn(true);

        // when
        boolean result = localLoginService.isEmailRegistered(new EmailCheckRequest("existing@test.com"));

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("올바른 이메일과 비밀번호로 로그인 시 토큰 쌍을 반환한다")
    void 올바른_이메일과_비밀번호로_로그인_시_토큰쌍을_반환한다() {
        // given
        String email = "test@test.com";
        String rawPassword = "password123!";
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "id", 1L);
        LocalAccount localAccount = LocalAccount.createLocalAccount(member, email, "encoded-password");
        TokenPairResponse expectedToken = TokenPairResponse.of(1L, "access", "refresh");

        given(localAccountRepository.findByEmail(email)).willReturn(Optional.of(localAccount));
        given(passwordEncoder.matches(rawPassword, "encoded-password")).willReturn(true);
        given(jwtTokenProvider.generateTokenPair(any(), any(), any())).willReturn(expectedToken);

        // when
        TokenPairResponse result = localLoginService.signIn(new LocalGuardianSignInRequest(email, rawPassword));

        // then
        assertThat(result).isEqualTo(expectedToken);
    }

    @Test
    @DisplayName("등록되지 않은 이메일로 로그인 시 BusinessException을 던진다")
    void 등록되지_않은_이메일로_로그인_시_예외가_발생한다() {
        // given
        given(localAccountRepository.findByEmail("notfound@test.com")).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> localLoginService.signIn(
                new LocalGuardianSignInRequest("notfound@test.com", "password")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 시 BusinessException을 던진다")
    void 잘못된_비밀번호로_로그인_시_예외가_발생한다() {
        // given
        String email = "test@test.com";
        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        LocalAccount localAccount = LocalAccount.createLocalAccount(member, email, "encoded-password");

        given(localAccountRepository.findByEmail(email)).willReturn(Optional.of(localAccount));
        given(passwordEncoder.matches("wrong-password", "encoded-password")).willReturn(false);

        // when & then
        assertThatThrownBy(() -> localLoginService.signIn(new LocalGuardianSignInRequest(email, "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PASSWORD);
    }

    @Test
    @DisplayName("SMS 인증한 이름·전화번호로 이메일 찾기를 요청하면 로컬 계정 이메일을 반환한다")
    void SMS_인증한_이름_전화번호로_이메일_찾기를_요청하면_로컬_계정_이메일을_반환한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        Member member = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        ReflectionTestUtils.setField(member, "localAccount",
                LocalAccount.createLocalAccount(member, "guardian@test.com", "encoded"));
        given(memberRepository.findTop2ByPhoneNumberAndName("01012345678", "홍길동")).willReturn(List.of(member));

        // when
        MemberInfoResponse response = localLoginService.findMemberByPhoneNumberAndName(
                new SmsVerificationRequest("홍길동", "01012345678"), httpRequest);

        // then
        assertThat(response.email()).isEqualTo("guardian@test.com");
    }

    @Test
    @DisplayName("SMS 인증 정보와 다른 이름·전화번호로 이메일 찾기를 요청하면 회원을 조회하지 않고 FORBIDDEN 예외가 발생한다")
    void SMS_인증_정보와_다른_전화번호로_이메일_찾기를_요청하면_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        // when & then
        assertThatThrownBy(() -> localLoginService.findMemberByPhoneNumberAndName(
                new SmsVerificationRequest("홍길동", "01099998888"), httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("임시 토큰 없이 이메일 찾기를 요청하면 회원을 조회하지 않고 UNAUTHORIZED 예외가 발생한다")
    void 임시_토큰_없이_이메일_찾기를_요청하면_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest))
                .willThrow(new BusinessException(ErrorCode.UNAUTHORIZED));

        // when & then
        assertThatThrownBy(() -> localLoginService.findMemberByPhoneNumberAndName(
                new SmsVerificationRequest("홍길동", "01012345678"), httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(memberRepository);
    }

    @Test
    @DisplayName("로컬 계정이 없는 회원의 이메일 찾기를 요청하면 MEMBER_NOT_FOUND 예외가 발생한다")
    void 로컬_계정이_없는_회원의_이메일_찾기를_요청하면_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        Member socialOnlyMember = Member.createMember(MemberType.GUARDIAN, "홍길동", "01012345678");
        given(memberRepository.findTop2ByPhoneNumberAndName("01012345678", "홍길동"))
                .willReturn(List.of(socialOnlyMember));

        // when & then
        assertThatThrownBy(() -> localLoginService.findMemberByPhoneNumberAndName(
                new SmsVerificationRequest("홍길동", "01012345678"), httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 이름과 전화번호의 회원이 둘 이상이면 이메일 찾기를 임의 회원으로 처리하지 않고 PHONE_NUMBER_SHARED_BY_MEMBERS 예외가 발생한다")
    void 같은_이름과_전화번호의_회원이_둘_이상이면_이메일_찾기에서_예외가_발생한다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01000000001");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        Member first = Member.createMember(MemberType.GUARDIAN, "홍길동", "01000000001");
        Member second = Member.createMember(MemberType.GUARDIAN, "홍길동", "01000000001");
        given(memberRepository.findTop2ByPhoneNumberAndName("01000000001", "홍길동"))
                .willReturn(List.of(first, second));

        // when & then
        assertThatThrownBy(() -> localLoginService.findMemberByPhoneNumberAndName(
                new SmsVerificationRequest("홍길동", "01000000001"), httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PHONE_NUMBER_SHARED_BY_MEMBERS);
    }

    @Test
    @DisplayName("같은 이름과 전화번호의 회원이 둘 이상이면 비밀번호 변경을 거부하고 어느 계정의 비밀번호도 바꾸지 않는다")
    void 같은_이름과_전화번호의_회원이_둘_이상이면_비밀번호를_바꾸지_않는다() {
        // given
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        TemporaryMember temporaryMember = TemporaryMember.createTemporaryMember("홍길동", "01000000001");
        given(temporaryMemberUtil.getTemporaryMemberFromRequest(httpRequest)).willReturn(temporaryMember);

        Member first = Member.createMember(MemberType.GUARDIAN, "홍길동", "01000000001");
        LocalAccount firstAccount = LocalAccount.createLocalAccount(first, "first@test.com", "encoded-first");
        ReflectionTestUtils.setField(first, "localAccount", firstAccount);
        Member second = Member.createMember(MemberType.GUARDIAN, "홍길동", "01000000001");
        LocalAccount secondAccount = LocalAccount.createLocalAccount(second, "second@test.com", "encoded-second");
        ReflectionTestUtils.setField(second, "localAccount", secondAccount);
        given(memberRepository.findTop2ByPhoneNumberAndName("01000000001", "홍길동"))
                .willReturn(List.of(first, second));

        // when & then
        assertThatThrownBy(() -> localLoginService.changePassword(
                new ChangePasswordRequest("newPass1!"), httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PHONE_NUMBER_SHARED_BY_MEMBERS);
        assertThat(firstAccount.getPassword()).isEqualTo("encoded-first");
        assertThat(secondAccount.getPassword()).isEqualTo("encoded-second");
    }
}
