package com.widyu.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.widyu.auth.infrastructure.AuthLimitStore;
import com.widyu.auth.infrastructure.ClientIpResolver;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.CoolsmsProperties;
import net.nurigo.sdk.message.model.Message;
import net.nurigo.sdk.message.service.DefaultMessageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("SmsService 단위 테스트")
class SmsServiceTest {

    @Mock
    private AuthLimitStore authLimitStore;

    @Mock
    private ClientIpResolver clientIpResolver;

    private static final CoolsmsProperties TEST_PROPERTIES = new CoolsmsProperties(
            "test-api-key",
            "test-api-secret",
            "https://api.coolsms.co.kr",
            "01000000000",
            6,
            300,
            "인증번호: {code}"
    );

    private SmsService createSmsService(DefaultMessageService messageService) {
        SmsService service = new SmsService(TEST_PROPERTIES, authLimitStore, clientIpResolver);
        ReflectionTestUtils.setField(service, "messageService", messageService);
        return service;
    }

    @Test
    @DisplayName("유효한 전화번호로 SMS 전송 시 인증 코드를 저장하고 메시지를 전송한다")
    void 유효한_전화번호로_SMS_전송_시_인증코드_저장하고_메시지_전송() throws Exception {
        // given
        DefaultMessageService mockSms = mock(DefaultMessageService.class);
        SmsService smsService = createSmsService(mockSms);

        // when
        smsService.sendVerificationSms("01012345678", "홍길동");

        // then
        verify(authLimitStore).saveCode(org.mockito.ArgumentMatchers.eq("01012345678"),
                org.mockito.ArgumentMatchers.matches("[0-9]{6}"), org.mockito.ArgumentMatchers.eq("홍길동"),
                org.mockito.ArgumentMatchers.eq(300));
        verify(mockSms).send(any(Message.class));
    }

    @Test
    @DisplayName("전화번호가 null이면 BusinessException을 던진다")
    void 전화번호가_null이면_예외가_발생한다() {
        // given
        SmsService smsService = createSmsService(mock(DefaultMessageService.class));

        // when & then
        assertThatThrownBy(() -> smsService.sendVerificationSms(null, "홍길동"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PHONE_NUMBER_REQUIRED);
    }

    @Test
    @DisplayName("전화번호가 빈 문자열이면 BusinessException을 던진다")
    void 전화번호가_빈_문자열이면_예외가_발생한다() {
        // given
        SmsService smsService = createSmsService(mock(DefaultMessageService.class));

        // when & then
        assertThatThrownBy(() -> smsService.sendVerificationSms("   ", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PHONE_NUMBER_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0201234567", "1234567890", "010123456789", "abc", "01023456"})
    @DisplayName("유효하지 않은 전화번호 형식이면 BusinessException을 던진다")
    void 유효하지_않은_전화번호_형식이면_예외가_발생한다(String phone) {
        // given
        SmsService smsService = createSmsService(mock(DefaultMessageService.class));

        // when & then
        assertThatThrownBy(() -> smsService.sendVerificationSms(phone, "홍길동"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_PHONE_NUMBER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"01012345678", "01112345678", "01612345678", "01712345678", "01812345678", "01912345678"})
    @DisplayName("01X로 시작하는 유효한 전화번호 형식은 정상 처리된다")
    void 유효한_전화번호_형식은_정상_처리된다(String phone) throws Exception {
        // given
        DefaultMessageService mockSms = mock(DefaultMessageService.class);
        SmsService smsService = createSmsService(mockSms);

        // when
        smsService.sendVerificationSms(phone, "홍길동");

        // then
        verify(mockSms).send(any(Message.class));
    }

    @Test
    @DisplayName("SMS 전송 중 일반 예외 발생 시 BusinessException을 던진다")
    void SMS_전송_중_예외_발생_시_예외가_발생한다() throws Exception {
        // given
        DefaultMessageService mockSms = mock(DefaultMessageService.class);
        SmsService smsService = createSmsService(mockSms);
        willThrow(new RuntimeException("네트워크 오류")).given(mockSms).send(any(Message.class));

        // when & then
        assertThatThrownBy(() -> smsService.sendVerificationSms("01012345678", "홍길동"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SMS_SEND_FAILED);
    }

    @Test
    @DisplayName("저장되는 VerificationCode에 전화번호와 이름이 포함된다")
    void 저장되는_인증코드에_전화번호와_이름이_포함된다() throws Exception {
        // given
        DefaultMessageService mockSms = mock(DefaultMessageService.class);
        SmsService smsService = createSmsService(mockSms);
        String phone = "01099998888";
        String name = "김철수";

        // when
        smsService.sendVerificationSms(phone, name);

        // then
        verify(authLimitStore).saveCode(org.mockito.ArgumentMatchers.eq(phone),
                org.mockito.ArgumentMatchers.matches("[0-9]{6}"), org.mockito.ArgumentMatchers.eq(name),
                org.mockito.ArgumentMatchers.eq(300));
    }
}
