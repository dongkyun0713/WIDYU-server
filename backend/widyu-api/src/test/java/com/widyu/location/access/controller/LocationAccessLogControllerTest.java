package com.widyu.location.access.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.global.error.GlobalExceptionHandler;
import com.widyu.global.util.SecurityUtil;
import com.widyu.location.access.application.LocationAccessLogService;
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
@DisplayName("LocationAccessLogController 페이지 파라미터 검증 테스트")
class LocationAccessLogControllerTest {

    @Mock private LocationAccessLogService locationAccessLogService;
    @Mock private SecurityUtil securityUtil;
    @InjectMocks private LocationAccessLogController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("size 101로 위치 열람 기록을 조회하면 400을 반환한다")
    void size_101로_위치_열람_기록을_조회하면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get("/api/v1/location/access-logs/mine").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size는 1 이상 100 이하여야 합니다."));
        verifyNoInteractions(locationAccessLogService);
    }
}
