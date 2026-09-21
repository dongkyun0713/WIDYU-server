package com.widyu.consent.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.widyu.consent.application.ConsentService;
import com.widyu.consent.repository.ConsentRecordRepository;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.util.SecurityUtil;
import com.widyu.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 빈 요청이 어떤 에러 코드로 나가는지 확인한다. 컬렉션에 {@code @NotEmpty}를 붙이면
 * Bean Validation이 서비스보다 먼저 걸려 LLD-0055 6절의 {@code CONSENT_4001} 대신
 * 일반 검증 400이 나간다. 실제 서비스를 물려야 드러나는 차이라 서비스를 목으로 두지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConsentController 빈 요청 응답 테스트")
class ConsentControllerTest {

    @Mock private SecurityUtil securityUtil;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        // 리포지토리는 필드로 두지 않는다. controller 패키지의 클래스가 Repository 타입 필드를
        // 가지면 ArchitectureBoundaryTest가 계층 위반으로 본다. 빈 요청은 리포지토리에
        // 닿기 전에 막히므로 여기서 쓸 일도 없다.
        ConsentService consentService = new ConsentService(
                mock(ConsentRecordRepository.class), mock(MemberRepository.class));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ConsentController(consentService, securityUtil))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("동의 항목이 빈 채로 제출하면 CONSENT_4001을 반환한다")
    void 동의_항목이_빈_채로_제출하면_CONSENT_4001을_반환한다() throws Exception {
        // given
        given(securityUtil.getCurrentMemberId()).willReturn(1023L);

        // when & then
        mockMvc.perform(put("/api/v1/consents")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "app-consent-v1",
                                  "consents": {}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUEST_EMPTY.getCode()));
    }

    @Test
    @DisplayName("동의 항목을 아예 빼고 제출해도 CONSENT_4001을 반환한다")
    void 동의_항목을_아예_빼고_제출해도_CONSENT_4001을_반환한다() throws Exception {
        // given
        given(securityUtil.getCurrentMemberId()).willReturn(1023L);

        // when & then
        mockMvc.perform(put("/api/v1/consents")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "app-consent-v1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUEST_EMPTY.getCode()));
    }

    @Test
    @DisplayName("철회할 항목이 빈 채로 요청하면 CONSENT_4001을 반환한다")
    void 철회할_항목이_빈_채로_요청하면_CONSENT_4001을_반환한다() throws Exception {
        // given
        given(securityUtil.getCurrentMemberId()).willReturn(1023L);

        // when & then
        mockMvc.perform(post("/api/v1/consents/withdrawal")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "keys": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.CONSENT_REQUEST_EMPTY.getCode()));
    }
}
