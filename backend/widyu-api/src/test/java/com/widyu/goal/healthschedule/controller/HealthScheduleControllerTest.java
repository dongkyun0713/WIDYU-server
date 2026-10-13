package com.widyu.goal.healthschedule.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.goal.healthschedule.application.HealthScheduleFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthScheduleController 입력 검증 테스트")
class HealthScheduleControllerTest {
    private static final String BASE_PATH = "/api/v1/goals/health-schedules";

    @Mock private HealthScheduleFacade healthScheduleFacade;
    @InjectMocks private HealthScheduleController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("일정 이름이 255자를 넘으면 400을 반환한다")
    void 일정_이름이_255자를_넘으면_400을_반환한다() throws Exception {
        // given
        String body = """
                {"scheduleName":"%s","scheduledAt":"2026-10-12T10:00:00"}
                """.formatted("가".repeat(300));

        // when & then
        mockMvc().perform(post(BASE_PATH + "/seniors").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("일정 이름은 255자 이하로 입력해 주세요 (scheduleName)"));
        verifyNoInteractions(healthScheduleFacade);
    }

    @Test
    @DisplayName("위도가 범위를 벗어나면 400을 반환한다")
    void 위도가_범위를_벗어나면_400을_반환한다() throws Exception {
        // given
        String body = """
                {"scheduleName":"병원","latitude":91.0,"scheduledAt":"2026-10-12T10:00:00"}
                """;

        // when & then
        mockMvc().perform(post(BASE_PATH + "/seniors").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("위도는 -90 이상 90 이하여야 합니다 (latitude)"));
        verifyNoInteractions(healthScheduleFacade);
    }

    @Test
    @DisplayName("일정 완료 요청에 일정 ID가 없으면 400을 반환한다")
    void 일정_완료_요청에_일정_ID가_없으면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(post(BASE_PATH + "/complete").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("일정 ID는 필수입니다 (healthScheduleId)"));
        verifyNoInteractions(healthScheduleFacade);
    }

    @Test
    @DisplayName("캘린더 조회 월이 13이면 400을 반환한다")
    void 캘린더_조회_월이_13이면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get(BASE_PATH + "/calendar/seniors").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("month는 1 이상 12 이하여야 합니다."));
        verifyNoInteractions(healthScheduleFacade);
    }
}
