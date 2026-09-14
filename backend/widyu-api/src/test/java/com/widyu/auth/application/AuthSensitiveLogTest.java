package com.widyu.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.auth.application.guardian.oauth.strategy.apple.AppleJwtUtils;
import com.widyu.auth.application.guardian.oauth.strategy.apple.AppleLoginStrategy;
import com.widyu.auth.dto.request.SocialLoginRequest;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.properties.AppleProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class AuthSensitiveLogTest {
    @Test
    @DisplayName("Apple 교환 오류를 DEBUG로 기록해도 합성 코드와 토큰과 응답 본문을 노출하지 않는다")
    void Apple_교환_오류는_DEBUG에서도_민감값을_노출하지_않는다() {
        // given
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://appleid.apple.com/auth/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Test-Secret", "synthetic-header-secret")
                        .body("{\"error\":\"synthetic-response-token\"}"));
        var jwt = mock(AppleJwtUtils.class);
        given(jwt.generateClientSecret("ios")).willReturn("synthetic-client-secret");
        var strategy = new AppleLoginStrategy(
                new AppleProperties("ios", "android", "team", "key", "unused", "https://example.com"),
                jwt, builder.build(), new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(AppleLoginStrategy.class);
        Level oldLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when / then
            assertThatThrownBy(() -> strategy.getUserInfo(SocialLoginRequest.builder()
                    .authorizationCode("synthetic-authorization-code").platform("ios").build()))
                    .isInstanceOf(BusinessException.class).hasNoCause();
            server.verify();
            assertThat(appender.list).isNotEmpty();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test
    @DisplayName("인증 예외를 처리하면 디버그 설정이 켜져도 원문을 응답과 로그에서 제거한다")
    void 인증_예외는_원문을_응답과_로그에서_제거한다() {
        // given
        var handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "includeDebugDetails", true);
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/guardians/sign-in/local");
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            // when
            var business = handler.handleBusinessException(
                    new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE, "synthetic-secret"), request);
            var system = handler.handleException(new IllegalStateException("synthetic-secret"), request);
            // then
            assertThat(business.getBody().getMessage()).doesNotContain("synthetic-secret");
            assertThat(business.getBody().getDebug()).isNull();
            assertThat(system.getBody().getDebug()).isNull();
            for (var event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-secret");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
