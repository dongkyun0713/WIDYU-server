package com.widyu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SmsFailureSafetyTest {
    @Mock private AuthLimitStore store;
    @Mock private ClientIpResolver ip;
    @Mock private DefaultMessageService sender;

    private SmsService service() {
        var properties = new CoolsmsProperties("synthetic-key", "synthetic-secret", "https://example.com",
                "01000000000", 6, 300, "code: {code}");
        var service = new SmsService(properties, store, ip);
        ReflectionTestUtils.setField(service, "messageService", sender);
        return service;
    }

    @Test
    @DisplayName("Redis 예약이 거부되면 코드를 저장하거나 문자를 발송하지 않는다")
    void 예약_거부는_문자_발송을_차단한다() {
        // given
        var service = service();
        given(ip.resolve()).willReturn("192.0.2.1");
        willThrow(new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE))
                .given(store).reserveSms("01000000000", "192.0.2.1");
        // when / then
        assertThatThrownBy(() -> service.sendVerificationSms("01000000000", "name"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        verify(store, never()).saveCode(anyString(), anyString(), anyString(), anyInt());
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("예약 후 코드 저장이 실패하면 문자를 발송하지 않는다")
    void 코드_저장_장애는_문자_발송을_차단한다() {
        // given
        var service = service();
        given(store.saveCode(anyString(), anyString(), anyString(), anyInt()))
                .willThrow(new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE));
        // when / then
        assertThatThrownBy(() -> service.sendVerificationSms("01000000000", "name"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTH_LIMIT_UNAVAILABLE);
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("공급자 오류에 민감값이 있으면 로그와 예외에서 제거하고 자기 코드를 폐기한다")
    void 공급자_민감값은_로그와_예외에_남기지_않는다() throws Exception {
        // given
        var service = service();
        given(store.saveCode(anyString(), anyString(), anyString(), anyInt())).willReturn("request-id");
        willThrow(new IllegalStateException("synthetic-secret code=123456 phone=01000000000"))
                .given(sender).send(any(Message.class));
        Logger logger = (Logger) LoggerFactory.getLogger(SmsService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when / then
            assertThatThrownBy(() -> service.sendVerificationSms("01000000000", "name"))
                    .isInstanceOf(BusinessException.class).hasNoCause()
                    .hasMessageNotContaining("synthetic-secret");
            verify(store).discardCode("01000000000", "request-id");
            assertThat(appender.list).isNotEmpty();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-secret", "123456", "01000000000");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
