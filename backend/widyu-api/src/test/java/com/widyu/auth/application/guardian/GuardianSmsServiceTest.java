package com.widyu.auth.application.guardian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.widyu.auth.application.SmsService;
import com.widyu.auth.application.VerificationCodeService;
import com.widyu.auth.dto.request.FindPasswordRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.dto.request.SmsVerificationRequest;
import com.widyu.auth.dto.response.TemporaryTokenResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianSmsService 단위 테스트")
class GuardianSmsServiceTest {

    @Mock private SmsService smsService;
    @Mock private VerificationCodeService verificationCodeService;
    @Mock private MemberRepository memberRepository;

    @InjectMocks
    private GuardianSmsService guardianSmsService;

    @Test
    @DisplayName("SMS 인증 요청 시 smsService에 전화번호와 이름으로 발송을 위임한다")
    void SMS_인증_요청_시_smsService에_발송을_위임한다() {
        // given
        SmsVerificationRequest request = new SmsVerificationRequest("홍길동", "01012341234");

        // when
        guardianSmsService.sendVerificationSms(request);

        // then
        verify(smsService).sendVerificationSms("01012341234", "홍길동");
    }

    @Test
    @DisplayName("존재하는 회원에게 비밀번호 찾기 SMS 발송 시 smsService에 위임한다")
    void 존재하는_회원에게_비밀번호_찾기_SMS_발송_시_smsService에_위임한다() {
        // given
        FindPasswordRequest request = new FindPasswordRequest("홍길동", "test@test.com", "01012341234");
        Member member = mock(Member.class);
        given(memberRepository.findByPhoneNumberAndNameAndLocalAccount_Email(
                "01012341234", "홍길동", "test@test.com"))
                .willReturn(Optional.of(member));

        // when
        guardianSmsService.sendVerificationSmsForPasswordReset(request);

        // then
        verify(smsService).sendVerificationSms("01012341234", "홍길동");
    }

    @Test
    @DisplayName("존재하지 않는 회원에게 비밀번호 찾기 SMS 발송 시 BusinessException을 던진다")
    void 존재하지_않는_회원에게_비밀번호_찾기_SMS_발송_시_예외가_발생한다() {
        // given
        FindPasswordRequest request = new FindPasswordRequest("없는사람", "none@test.com", "01099999999");
        given(memberRepository.findByPhoneNumberAndNameAndLocalAccount_Email(
                "01099999999", "없는사람", "none@test.com"))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> guardianSmsService.sendVerificationSmsForPasswordReset(request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.MEMBER_NOT_FOUND);
    }

    @Test
    @DisplayName("비밀번호 찾기 대상 회원이 없으면 입력값 없이 WARN 로그만 남긴다")
    void 비밀번호_찾기_대상_회원이_없으면_입력값_없이_WARN_로그만_남긴다() {
        // given
        FindPasswordRequest request = new FindPasswordRequest("없는사람", "none@test.com", "01099999999");
        given(memberRepository.findByPhoneNumberAndNameAndLocalAccount_Email(
                "01099999999", "없는사람", "none@test.com"))
                .willReturn(Optional.empty());
        Logger logger = (Logger) LoggerFactory.getLogger(GuardianSmsService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            assertThatThrownBy(() -> guardianSmsService.sendVerificationSmsForPasswordReset(request))
                    .isInstanceOf(BusinessException.class);

            // then
            ILoggingEvent failure = appender.list.get(appender.list.size() - 1);
            assertThat(failure.getLevel()).isEqualTo(Level.WARN);
            assertThat(failure.getFormattedMessage()).isEqualTo("비밀번호 찾기 대상 회원 미일치");
            assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("SMS 인증 코드 검증 시 verificationCodeService에 위임하여 임시 토큰을 반환한다")
    void SMS_인증_코드_검증_시_verificationCodeService에_위임하여_임시_토큰_반환한다() {
        // given
        SmsCodeRequest request = new SmsCodeRequest("01012341234", "123456");
        TemporaryTokenResponse expectedResponse = TemporaryTokenResponse.from("temp-token-value");
        given(verificationCodeService.verifyAndIssueTemporaryToken("01012341234", "123456"))
                .willReturn(expectedResponse);

        // when
        TemporaryTokenResponse response = guardianSmsService.verifyCodeAndIssueToken(request);

        // then
        assertThat(response.temporaryToken()).isEqualTo("temp-token-value");
    }
}
