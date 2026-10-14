package com.widyu.admin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.admin.application.AdminFcmService;
import com.widyu.admin.application.AdminMemberService;
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
@DisplayName("AdminMemberController 페이지 파라미터 검증 테스트")
class AdminMemberControllerTest {
    private static final String LIST_PATH = "/api/v1/admin/members/list";

    @Mock private AdminMemberService adminMemberService;
    @Mock private AdminFcmService adminFcmService;
    @InjectMocks private AdminMemberController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("음수 page로 회원 목록을 조회하면 400을 반환한다")
    void 음수_page로_회원_목록을_조회하면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get(LIST_PATH).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page는 0 이상이어야 합니다 (page)"));
        verifyNoInteractions(adminFcmService);
    }

    @Test
    @DisplayName("size 0으로 회원 목록을 조회하면 400을 반환한다")
    void size_0으로_회원_목록을_조회하면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get(LIST_PATH).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size는 1 이상 100 이하여야 합니다 (size)"));
        verifyNoInteractions(adminFcmService);
    }
}
