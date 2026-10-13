package com.widyu.goal.walk.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.goal.walk.application.WalkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalkController 조회 파라미터 검증 테스트")
class WalkControllerTest {

    @Mock private WalkService walkService;
    @InjectMocks private WalkController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("월별 걸음 통계 조회 연도가 범위를 벗어나면 400을 반환한다")
    void 월별_걸음_통계_조회_연도가_범위를_벗어나면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get("/api/v1/goals/walks/monthly").param("year", "999999999").param("month", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("year는 2000 이상 2100 이하여야 합니다 (year)"));
        verifyNoInteractions(walkService);
    }
}
