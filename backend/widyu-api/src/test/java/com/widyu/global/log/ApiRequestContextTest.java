package com.widyu.global.log;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import io.swagger.v3.oas.annotations.Operation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

class ApiRequestContextTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("문서 인터페이스에 Swagger summary가 있으면 기능 이름으로 반환한다")
    void 문서_인터페이스에_Swagger_summary가_있으면_기능_이름으로_반환한다() throws Exception {
        // given
        MockHttpServletRequest request = requestHandledBy("documented");

        // when
        String apiName = ApiRequestContext.apiName(request);

        // then
        assertThat(apiName).isEqualTo("앨범 업로드 완료");
    }

    @Test
    @DisplayName("Swagger summary가 없으면 컨트롤러와 메서드 이름을 반환한다")
    void Swagger_summary가_없으면_컨트롤러와_메서드_이름을_반환한다() throws Exception {
        // given
        MockHttpServletRequest request = requestHandledBy("undocumented");

        // when
        String apiName = ApiRequestContext.apiName(request);

        // then
        assertThat(apiName).isEqualTo("SampleController.undocumented");
    }

    @Test
    @DisplayName("핸들러가 정해지기 전에 실패하면 알 수 없는 API와 실제 URI가 아닌 하이픈 경로를 반환한다")
    void 핸들러가_정해지기_전에_실패하면_알_수_없는_API와_하이픈_경로를_반환한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/albums/9");

        // when
        String apiName = ApiRequestContext.apiName(request);
        String path = ApiRequestContext.pathPattern(request);

        // then
        assertThat(apiName).isEqualTo(ApiRequestContext.UNKNOWN_API);
        assertThat(path).isEqualTo(ApiRequestContext.NONE);
    }

    @Test
    @DisplayName("매핑 패턴이 있으면 실제 URI 대신 패턴을 반환한다")
    void 매핑_패턴이_있으면_실제_URI_대신_패턴을_반환한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/albums/9");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/albums/{albumId}");

        // when
        String path = ApiRequestContext.pathPattern(request);

        // then
        assertThat(path).isEqualTo("/api/v1/albums/{albumId}");
    }

    @Test
    @DisplayName("로그인한 요청이면 역할과 회원 ID를 요청자로 반환한다")
    void 로그인한_요청이면_역할과_회원_ID를_요청자로_반환한다() {
        // given
        PrincipalDetails principal = new PrincipalDetails(12L, MemberRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        // when
        String actor = ApiRequestContext.actor();

        // then
        assertThat(actor).isEqualTo("USER#12");
    }

    @Test
    @DisplayName("비로그인 요청이면 요청자를 anonymous로 반환한다")
    void 비로그인_요청이면_요청자를_anonymous로_반환한다() {
        // when
        String actor = ApiRequestContext.actor();

        // then
        assertThat(actor).isEqualTo(ApiRequestContext.ANONYMOUS);
    }

    @Test
    @DisplayName("대상은 ID 경로 변수만 남기고 결제 키 같은 값은 뺀다")
    void 대상은_ID_경로_변수만_남기고_결제_키_같은_값은_뺀다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("seniorId", "34");
        variables.put("paymentKey", "secret-payment-key");
        variables.put("albumId", "7");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);

        // when
        String target = ApiRequestContext.target(request);

        // then
        assertThat(target).isEqualTo("albumId=7,seniorId=34");
    }

    @Test
    @DisplayName("경로 변수가 없으면 대상을 하이픈으로 반환한다")
    void 경로_변수가_없으면_대상을_하이픈으로_반환한다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("paymentKey", "k"));

        // when
        String target = ApiRequestContext.target(request);

        // then
        assertThat(target).isEqualTo(ApiRequestContext.NONE);
    }

    private static MockHttpServletRequest requestHandledBy(String methodName) throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/x");
        HandlerMethod handler = new HandlerMethod(new SampleController(), SampleController.class.getMethod(methodName));
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
        return request;
    }

    interface SampleDocs {
        @Operation(summary = "앨범 업로드 완료")
        void documented();
    }

    static class SampleController implements SampleDocs {
        @Override
        public void documented() {
        }

        public void undocumented() {
        }
    }
}
