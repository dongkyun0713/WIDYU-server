package com.widyu.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.auth.TemporaryMember;
import com.widyu.auth.application.SmsService;
import com.widyu.auth.application.VerificationCodeService;
import com.widyu.auth.application.guardian.local.LocalLoginService;
import com.widyu.auth.dto.response.LocalSignupResponse;
import com.widyu.auth.repository.TemporaryMemberRepository;
import com.widyu.fcm.application.FcmTransport;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SocialAccount;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "firebase.config-path=/dev/null",
        "s3.credentials.access-key=test",
        "s3.credentials.secret-key=test",
        "s3.region.statics=ap-northeast-2",
        "s3.bucket-name=test-bucket",
        "coolsms.api-key=test",
        "coolsms.api-secret=test",
        "coolsms.api-url=https://api.coolsms.co.kr",
        "coolsms.from-phone-number=01000000000",
        "coolsms.verification-code-length=6",
        "coolsms.verification-code-ttl=300",
        "coolsms.message-template=인증번호: {code}"
})
@DisplayName("로컬 보호자 회원가입 통합 테스트")
class LocalGuardianSignupIntegrationTest {
    @MockBean private FcmTransport fcmTransport;

    @Autowired private VerificationCodeService verificationCodeService;
    @Autowired private LocalLoginService localLoginService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private LocalAccountRepository localAccountRepository;

    // Redis 레포지토리는 H2 테스트 환경에서 MockBean으로 대체
    @MockBean private TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private com.widyu.auth.repository.RefreshTokenRepository refreshTokenRepository;
    // H2 가입 흐름에서는 제한 인프라를 대체한다. 원자성은 AuthLimitStoreRedisTest에서 실제 Redis로 검증한다.
    @MockBean private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @MockBean private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;
    // 외부 서비스 MockBean
    @MockBean private S3Client s3Client;
    @MockBean private SmsService smsService;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @AfterEach
    void tearDown() {
        localAccountRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("임시 회원으로 이메일+비밀번호 회원가입 시 토큰을 반환한다")
    void signupFlow_withTemporaryMember_returnsTokenPair() {
        // given
        TemporaryMember tempMember = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        String email = "test@test.com";
        String rawPassword = "password123!";

        // when
        LocalSignupResponse response = localLoginService.signupGuardianWithLocal(
                tempMember, email, rawPassword
        );

        // then
        assertThat(response).isNotNull();
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(localAccountRepository.existsByEmail(email)).isTrue();
    }

    @Test
    @DisplayName("동일 이메일로 중복 회원가입 시 BusinessException을 던진다")
    void signupFlow_duplicateEmail_throwsBusinessException() {
        // given
        TemporaryMember temp1 = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        TemporaryMember temp2 = TemporaryMember.createTemporaryMember("김철수", "01098765432");
        String email = "duplicate@test.com";

        localLoginService.signupGuardianWithLocal(temp1, email, "password1!");

        // when & then
        assertThatThrownBy(() -> localLoginService.signupGuardianWithLocal(temp2, email, "password2!"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REGISTERED_EMAIL);
    }

    @Test
    @DisplayName("시니어 회원과 같은 전화번호와 이름으로 가입하면 ALREADY_REGISTERED_PHONE_NUMBER 예외가 발생하고 로컬 계정을 저장하지 않는다")
    void 시니어_회원과_같은_전화번호와_이름으로_가입하면_거부한다() {
        // given
        memberRepository.save(Member.createMember(MemberType.SENIOR, "가상시니어", "01000000002"));
        long memberCountBefore = memberRepository.count();
        TemporaryMember temp = TemporaryMember.createTemporaryMember("가상시니어", "01000000002");

        // when & then
        assertThatThrownBy(() -> localLoginService.signupGuardianWithLocal(temp, "senior-local@test.com", "password1!"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REGISTERED_PHONE_NUMBER);
        assertThat(localAccountRepository.count()).isZero();
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore);
    }

    @Test
    @DisplayName("소셜 보호자와 같은 전화번호로 가입하면 ALREADY_REGISTERED_PHONE_NUMBER 예외가 발생하고 로컬 계정을 저장하지 않는다")
    void 소셜_보호자와_같은_전화번호로_가입하면_거부한다() {
        // given
        Member socialGuardian = Member.createMember(MemberType.GUARDIAN, "가상보호자", "01000000003");
        socialGuardian.getSocialAccounts().add(
                SocialAccount.createSocialAccount("social@test.com", "kakao", "virtual-oauth-id", socialGuardian));
        memberRepository.save(socialGuardian);
        long memberCountBefore = memberRepository.count();
        TemporaryMember temp = TemporaryMember.createTemporaryMember("다른이름", "01000000003");

        // when & then
        assertThatThrownBy(() -> localLoginService.signupGuardianWithLocal(temp, "guardian-local@test.com", "password1!"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REGISTERED_PHONE_NUMBER);
        assertThat(localAccountRepository.count()).isZero();
        assertThat(memberRepository.count()).isEqualTo(memberCountBefore);
    }

    @Test
    @DisplayName("로컬 계정이 있는 회원의 전화번호로 다른 이메일로 다시 가입하면 ALREADY_REGISTERED_PHONE_NUMBER 예외가 발생한다")
    void 로컬_계정이_있는_회원의_전화번호로_다시_가입하면_거부한다() {
        // given
        localLoginService.signupGuardianWithLocal(
                TemporaryMember.createTemporaryMember("가상보호자", "01000000004"), "first@test.com", "password1!");
        TemporaryMember temp = TemporaryMember.createTemporaryMember("가상보호자", "01000000004");

        // when & then
        assertThatThrownBy(() -> localLoginService.signupGuardianWithLocal(temp, "second@test.com", "password2!"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ALREADY_REGISTERED_PHONE_NUMBER);
        assertThat(localAccountRepository.existsByEmail("second@test.com")).isFalse();
    }

    @Test
    @DisplayName("회원가입 후 로그인 시 유효한 토큰을 반환한다")
    void signupThenSignIn_returnsValidToken() {
        // given
        TemporaryMember temp = TemporaryMember.createTemporaryMember("홍길동", "01012345678");
        String email = "signin@test.com";
        String password = "password123!";
        localLoginService.signupGuardianWithLocal(temp, email, password);

        // when
        var tokenPair = localLoginService.signIn(
                new com.widyu.auth.dto.request.LocalGuardianSignInRequest(email, password)
        );

        // then
        assertThat(tokenPair).isNotNull();
        assertThat(tokenPair.accessToken()).isNotBlank();
    }
}
