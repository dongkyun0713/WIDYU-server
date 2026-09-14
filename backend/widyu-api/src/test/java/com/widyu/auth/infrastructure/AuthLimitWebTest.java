package com.widyu.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.request.SmsCodeRequest;
import com.widyu.auth.exception.AuthRateLimitException;
import com.widyu.global.config.AuthLimitWebConfig;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.properties.AuthLimitProperties;
import com.widyu.global.properties.AuthProxyProperties;
import java.util.List;
import jakarta.validation.Validation;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class AuthLimitWebTest {
    @Test
    @DisplayName("위조한 forwarding 헤더가 있어도 직접 연결 IP를 반환한다")
    void 위조_forwarding_헤더는_IP를_바꾸지_않는다() {
        // given
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1");
        request.addHeader("Forwarded", "for=198.51.100.2");
        // when / then
        request.setAttribute(ClientIpResolver.ORIGINAL_REQUEST,
                new ClientIpResolver.OriginalRequest("192.0.2.1", List.of("198.51.100.1")));
        assertThat(new ClientIpResolver(request, new AuthProxyProperties(null)).resolve()).isEqualTo("192.0.2.1");
    }

    @Test
    @DisplayName("주소 변경 valve가 있으면 그 앞에서 원래 peer를 보존한다")
    void forwarding_앞에_peer_보존을_배치한다() {
        // given / when / then
        var config = new AuthLimitWebConfig();
        var factory = new TomcatServletWebServerFactory();
        factory.addEngineValves(new RemoteIpValve());
        config.customize(factory);
        assertThat(factory.getEngineValves()).extracting(Object::getClass)
                .containsExactly(AuthLimitWebConfig.OriginalPeerValve.class, RemoteIpValve.class);
    }

    @Test
    @DisplayName("기본 설정을 바인딩하면 미승인 기술 기본값과 미설정 전체 예산을 유지한다")
    void 기본_설정은_기술_기본값과_미설정_전체예산을_유지한다() {
        // given
        var binder = new Binder(new MapConfigurationPropertySource(Map.of()));
        // when
        var properties = binder.bindOrCreate("auth.limits", Bindable.of(AuthLimitProperties.class));
        // then
        assertThat(properties).isEqualTo(new AuthLimitProperties(60, 3, 10, 30, null, 900, 10, 100));
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                "systemEnvironment", Map.of("AUTH_LIMITS_SMSGLOBALDAY", "123")));
        assertThat(Binder.get(environment).bindOrCreate("auth.limits", Bindable.of(AuthLimitProperties.class))
                .smsGlobalDay()).isEqualTo(123);
    }

    @Test
    @DisplayName("한도 초과와 저장소 장애를 HTTP 응답으로 변환하면 429 재시도와 503을 반환한다")
    void 한도와_장애를_HTTP_응답으로_변환한다() throws Exception {
        // given
        var mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // when / then
        mvc.perform(post("/api/v1/auth/test/limited")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.code").value("AUTH_4290"));
        mvc.perform(post("/api/v1/auth/test/unavailable")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_5030"))
                .andExpect(jsonPath("$.message").value(ErrorCode.AUTH_LIMIT_UNAVAILABLE.getMessage()));
    }

    @Test
    @DisplayName("인증 입력이 비어 있거나 코드에 문자가 있으면 DTO 검증이 거부한다")
    void 잘못된_인증_입력을_DTO_검증이_거부한다() {
        // given
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            // when / then
            assertThat(validator.validate(new LocalGuardianSignInRequest("", ""))).isNotEmpty();
            assertThat(validator.validate(new LocalGuardianSignInRequest("a@example.com", "x".repeat(257)))).isNotEmpty();
            assertThat(validator.validate(new SmsCodeRequest("01000000000", "abcdef"))).isNotEmpty();
            assertThat(validator.validate(new SmsCodeRequest("01000000000", "123456"))).isEmpty();
        }
    }

    @Test
    @DisplayName("Naver 테스트 컨트롤러는 클래스 경로에 존재하지 않는다")
    void Naver_테스트_컨트롤러가_제거된다() {
        // given / when / then
        assertThatThrownBy(() -> Class.forName("com.widyu.auth.controller.NaverLoginController"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @RestController
    static class TestController {
        @PostMapping("/api/v1/auth/test/limited")
        void limited() {
            throw new AuthRateLimitException(1001);
        }

        @PostMapping("/api/v1/auth/test/unavailable")
        void unavailable() {
            throw new BusinessException(ErrorCode.AUTH_LIMIT_UNAVAILABLE, "synthetic-secret");
        }
    }
}
