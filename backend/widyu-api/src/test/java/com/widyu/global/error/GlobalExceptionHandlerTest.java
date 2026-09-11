package com.widyu.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.response.ApiResponseTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 예외가 실제 성격에 맞는 HTTP 상태코드로 응답되는지 검증한다.
 * <p>
 * mock할 협력 객체가 없고 "예외 → 상태코드 라우팅" 자체가 검증 대상이므로
 * MockitoExtension/BDDMockito 대신 MockMvc standalone + 더미 컨트롤러를 사용한다.
 * (같은 저장소의 {@code PaymentControllerTest}와 동일한 standaloneSetup 방식)
 */
@DisplayName("GlobalExceptionHandler 에러 상태코드 테스트")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();

        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("요청 본문 JSON이 깨져 있으면 400을 반환한다")
    void 깨진_JSON_본문() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(APPLICATION_JSON)
                        .content("{ this is broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }

    @Test
    @DisplayName("경로 변수 타입이 맞지 않으면 400을 반환한다")
    void 경로_변수_타입_불일치() throws Exception {
        mockMvc.perform(get("/test/type/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("요청 값의 타입이 올바르지 않습니다 (id)"));
    }

    @Test
    @DisplayName("필수 요청 파라미터가 누락되면 400을 반환한다")
    void 필수_파라미터_누락() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("필수 요청 파라미터가 누락되었습니다 (name)"));
    }

    @Test
    @DisplayName("필수 multipart 파트가 누락되면 400을 반환한다")
    void 필수_파트_누락() throws Exception {
        mockMvc.perform(multipart("/test/part"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.message").value("필수 요청 파트가 누락되었습니다 (file)"));
    }

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드로 호출하면 405를 반환한다")
    void 지원하지_않는_메서드() throws Exception {
        mockMvc.perform(delete("/test/param"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
    }

    @Test
    @DisplayName("지원하지 않는 미디어 타입으로 호출하면 415를 반환한다")
    void 지원하지_않는_미디어_타입() throws Exception {
        mockMvc.perform(post("/test/body")
                        .contentType(TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getCode()));
    }

    @Test
    @DisplayName("401로 재분류된 BusinessException은 401을 반환한다")
    void 재분류된_401_비즈니스_예외() throws Exception {
        mockMvc.perform(get("/test/business/apple-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.APPLE_ID_TOKEN_INVALID.getCode()));
    }

    @Test
    @DisplayName("502로 재분류된 BusinessException은 502를 반환한다")
    void 재분류된_502_비즈니스_예외() throws Exception {
        mockMvc.perform(get("/test/business/naver"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(ErrorCode.NAVER_COMMUNICATION_ERROR.getCode()));
    }

    @Test
    @DisplayName("파일 검증 BusinessException은 400과 명시적 메시지를 반환한다")
    void 파일_검증_비즈니스_예외는_400과_메시지를_반환한다() throws Exception {
        mockMvc.perform(get("/test/business/invalid-file-type"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_FILE_TYPE.getCode()))
                .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_FILE_TYPE.getMessage()));
    }

    @Test
    @DisplayName("상세 메시지가 있는 BusinessException은 응답 메시지에 상세 내용을 포함한다")
    void 상세_메시지_비즈니스_예외는_응답에_상세_내용을_포함한다() throws Exception {
        mockMvc.perform(get("/test/business/file-too-large"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.FILE_TOO_LARGE.getCode()))
                .andExpect(jsonPath("$.message").value("파일 크기가 너무 큽니다. - 사진은 최대 10MB까지 업로드 가능합니다."));
    }

    @Test
    @DisplayName("업로드 파일 크기를 초과하면 413을 반환한다")
    void 파일_크기_초과() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponseTemplate<Void>> response =
                handler.handleMaxUploadSizeExceededException(new MaxUploadSizeExceededException(100L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.getCode());
    }

    @Test
    @DisplayName("포인트 낙관적 락 재시도가 소진되면 409(POINT_CONCURRENT_UPDATE)를 반환한다")
    void 낙관적_락_충돌_소진() throws Exception {
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.POINT_CONCURRENT_UPDATE.getCode()));
    }

    @Test
    @DisplayName("존재하지 않는 리소스를 요청하면 404를 반환한다")
    void 존재하지_않는_리소스() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponseTemplate<Void>> response =
                handler.handleNoResourceFoundException(new NoResourceFoundException(HttpMethod.GET, "/not-exist"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("개발 환경의 500 응답은 예외 유형과 요청 경로를 포함한다")
    void 개발_환경의_500_응답은_예외_유형과_요청_경로를_포함한다() {
        // given
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "includeDebugDetails", true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");

        // when
        ResponseEntity<ApiResponseTemplate<Void>> response =
                handler.handleException(new IllegalStateException("sensitive detail"), request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDebug().exceptionType()).isEqualTo("IllegalStateException");
        assertThat(response.getBody().getDebug().requestUri()).isEqualTo("/api/test");
    }

    @RestController
    static class TestController {

        @PostMapping(value = "/test/body", consumes = "application/json")
        void body(@RequestBody final SampleRequest request) {
        }

        @GetMapping("/test/type/{id}")
        void type(@PathVariable final Long id) {
        }

        @GetMapping("/test/param")
        void param(@RequestParam final String name) {
        }

        @PostMapping("/test/part")
        void part(@RequestPart final MultipartFile file) {
        }

        @GetMapping("/test/business/apple-token")
        void appleToken() {
            throw new BusinessException(ErrorCode.APPLE_ID_TOKEN_INVALID);
        }

        @GetMapping("/test/business/naver")
        void naver() {
            throw new BusinessException(ErrorCode.NAVER_COMMUNICATION_ERROR);
        }

        @GetMapping("/test/optimistic-lock")
        void optimisticLock() {
            throw new ObjectOptimisticLockingFailureException("SeniorProfile", 1L);
        }
        
        @GetMapping("/test/business/invalid-file-type")
        void invalidFileType() {
            throw new BusinessException(ErrorCode.INVALID_FILE_TYPE);
        }

        @GetMapping("/test/business/file-too-large")
        void fileTooLarge() {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "사진은 최대 10MB까지 업로드 가능합니다.");
        }
    }

    record SampleRequest(String value) {
    }
}
