package com.widyu.admin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.admin.application.AdminAccessLogService;
import com.widyu.global.error.GlobalExceptionHandler;
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
@DisplayName("AdminAccessLogController 페이지 파라미터 검증 테스트")
class AdminAccessLogControllerTest {

    @Mock private AdminAccessLogService adminAccessLogService;
    @InjectMocks private AdminAccessLogController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("음수 page로 접속기록을 조회하면 400을 반환한다")
    void 음수_page로_접속기록을_조회하면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get("/api/v1/admin/access-logs").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page는 0 이상이어야 합니다 (page)"));
        verifyNoInteractions(adminAccessLogService);
    }
}
