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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.global.response.ApiResponseTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.sql.SQLException;
import java.util.List;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST_FORMAT.getCode()))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    @DisplayName("경로 변수 타입이 맞지 않으면 400을 반환한다")
    void 경로_변수_타입_불일치() throws Exception {
        mockMvc.perform(get("/test/type/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST_FORMAT.getCode()))
                .andExpect(jsonPath("$.message").value("요청 값의 타입이 올바르지 않습니다 (id)"))
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    @DisplayName("필수 요청 파라미터가 누락되면 400을 반환한다")
    void 필수_파라미터_누락() throws Exception {
        mockMvc.perform(get("/test/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_REQUIRED_VALUE.getCode()))
                .andExpect(jsonPath("$.message").value("필수 요청 파라미터가 누락되었습니다 (name)"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @DisplayName("필수 multipart 파트가 누락되면 400을 반환한다")
    void 필수_파트_누락() throws Exception {
        mockMvc.perform(multipart("/test/part"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_REQUIRED_VALUE.getCode()))
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

    @Test
    @DisplayName("처리하지 못한 예외는 기능 이름과 경로 패턴과 대상을 시스템 로그에 남긴다")
    void 처리하지_못한_예외는_기능_이름과_경로_패턴과_대상을_시스템_로그에_남긴다() throws Exception {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            mockMvc.perform(post("/test/system/7")).andExpect(status().isInternalServerError());

            // then
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("[EXCEPTION] ")
                    .contains(" Api=TestController.systemFailure Method=POST Path=/test/system/{albumId} "
                            + "Actor=anonymous Target=albumId=7 ExceptionType=java.lang.IllegalStateException ");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("5xx로 분류된 업무 예외는 API 실패 알림이 읽는 서버 오류 로그로 남긴다")
    void _5xx로_분류된_업무_예외는_API_실패_알림이_읽는_서버_오류_로그로_남긴다() throws Exception {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            mockMvc.perform(get("/test/business/naver")).andExpect(status().isBadGateway());

            // then
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .startsWith("[EXCEPTION] ")
                        .contains(" ExceptionType=com.widyu.global.error.BusinessException ");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("4xx 업무 예외는 알림 대상이 아닌 WARN 업무 로그로 남긴다")
    void _4xx_업무_예외는_알림_대상이_아닌_WARN_업무_로그로_남긴다() throws Exception {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            mockMvc.perform(get("/test/business/invalid-file-type")).andExpect(status().isBadRequest());

            // then
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).startsWith("[BUSINESS-EXCEPTION] ");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("요청 본문 검증이 여러 필드에서 실패하면 필드별 사유를 모두 담아 REQ_4001로 응답한다")
    void 요청_본문_검증이_여러_필드에서_실패하면_필드별_사유를_모두_담아_응답한다() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\": \"\", \"code\": \"ABCDE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')].reason").value("이름은 필수입니다."))
                .andExpect(jsonPath("$.errors[?(@.field == 'code')].reason").value("코드는 3자 이하입니다."));
    }

    @Test
    @DisplayName("JSON 값의 형식이 틀리면 틀린 위치를 필드 경로로 담아 REQ_4002로 응답한다")
    void JSON_값의_형식이_틀리면_틀린_위치를_필드_경로로_담아_응답한다() throws Exception {
        mockMvc.perform(post("/test/typed")
                        .contentType(APPLICATION_JSON)
                        .content("{\"items\": [{\"count\": \"many\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST_FORMAT.getCode()))
                .andExpect(jsonPath("$.errors[0].field").value("items[0].count"));
    }

    @Test
    @DisplayName("필수 헤더가 없으면 500이 아니라 REQ_4003으로 응답한다")
    void 필수_헤더가_없으면_REQ_4003으로_응답한다() throws Exception {
        mockMvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_REQUIRED_VALUE.getCode()))
                .andExpect(jsonPath("$.errors[0].field").value("X-Device-Id"));
    }

    @Test
    @DisplayName("DB 길이 초과는 400과 해당 필드로 응답하고 검증 누락 신호로 서버 오류 로그를 남긴다")
    void DB_길이_초과는_400과_해당_필드로_응답하고_서버_오류_로그를_남긴다() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            mockMvc.perform(get("/test/db/too-long"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.INPUT_TOO_LONG.getCode()))
                    .andExpect(jsonPath("$.errors[0].field").value("scheduleName"))
                    .andExpect(jsonPath("$.message").value("허용 길이를 넘었습니다 (scheduleName)"));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).startsWith("[EXCEPTION] ");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("DB 중복 키는 409 REQ_4090으로 응답한다")
    void DB_중복_키는_409로_응답한다() throws Exception {
        mockMvc.perform(get("/test/db/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.DUPLICATE_RESOURCE.getCode()));
    }

    @Test
    @DisplayName("그 밖의 DB 무결성 위반은 일반 500과 구분되는 SRV_5001로 응답하고 dev에서는 원인 메시지를 보여 준다")
    void 그_밖의_DB_무결성_위반은_SRV_5001로_응답하고_dev에서는_원인_메시지를_보여_준다() throws Exception {
        mockMvcWithDebugDetails().perform(get("/test/db/not-null"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ErrorCode.DATA_INTEGRITY_ERROR.getCode()))
                .andExpect(jsonPath("$.debug.exceptionType").value("DataIntegrityViolationException"))
                .andExpect(jsonPath("$.debug.message").value("Column 'member_fcm_token_id' cannot be null"));
    }

    @Test
    @DisplayName("prod처럼 디버그 정보를 끄면 5xx 응답에 원인 메시지가 없다")
    void 디버그_정보를_끄면_5xx_응답에_원인_메시지가_없다() throws Exception {
        mockMvc.perform(get("/test/db/not-null"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.debug").doesNotExist());
    }

    @Test
    @DisplayName("락 대기 실패는 잠시 후 재시도할 수 있는 503 SRV_5031로 응답한다")
    void 락_대기_실패는_503_SRV_5031로_응답한다() throws Exception {
        mockMvc.perform(get("/test/lock"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.TEMPORARILY_BUSY.getCode()));
    }

    @Test
    @DisplayName("Redis 연결 실패는 의존 시스템 장애인 503 SRV_5032로 응답한다")
    void Redis_연결_실패는_503_SRV_5032로_응답한다() throws Exception {
        mockMvc.perform(get("/test/redis"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode()));
    }

    private MockMvc mockMvcWithDebugDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ReflectionTestUtils.setField(handler, "includeDebugDetails", true);
        return MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(handler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .build();
    }

    @RestController
    static class TestController {

        @PostMapping(value = "/test/valid", consumes = "application/json")
        void valid(@Valid @RequestBody final ValidatedRequest request) {
        }

        @PostMapping(value = "/test/typed", consumes = "application/json")
        void typed(@RequestBody final TypedRequest request) {
        }

        @GetMapping("/test/header")
        void header(@RequestHeader("X-Device-Id") final String deviceId) {
        }

        @GetMapping("/test/db/too-long")
        void tooLong() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("Data too long for column 'schedule_name' at row 1", "22001", 1406));
        }

        @GetMapping("/test/db/duplicate")
        void duplicate() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("Duplicate entry 'a' for key 'uk_member'", "23000", 1062));
        }

        @GetMapping("/test/db/not-null")
        void notNull() {
            throw new DataIntegrityViolationException("could not execute statement",
                    new SQLException("Column 'member_fcm_token_id' cannot be null", "23000", 1048));
        }

        @GetMapping("/test/lock")
        void lock() {
            throw new CannotAcquireLockException("Lock wait timeout exceeded");
        }

        @GetMapping("/test/redis")
        void redis() {
            throw new RedisConnectionFailureException("Unable to connect to Redis");
        }

        @PostMapping("/test/system/{albumId}")
        void systemFailure(@PathVariable final Long albumId) {
            throw new IllegalStateException("처리 실패");
        }

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

    record ValidatedRequest(
            @NotBlank(message = "이름은 필수입니다.") String name,
            @Size(max = 3, message = "코드는 3자 이하입니다.") String code
    ) {
    }

    record TypedRequest(List<Item> items) {
        record Item(Integer count) {
        }
    }
}
