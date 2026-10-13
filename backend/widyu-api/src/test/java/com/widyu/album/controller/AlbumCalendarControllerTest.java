package com.widyu.album.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.widyu.album.application.AlbumCalendarService;
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
@DisplayName("AlbumCalendarController 날짜 파라미터 검증 테스트")
class AlbumCalendarControllerTest {
    private static final String BASE_PATH = "/api/v1/albums/calendar";

    @Mock private AlbumCalendarService albumCalendarService;
    @InjectMocks private AlbumCalendarController controller;

    private MockMvc mockMvc() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("존재하지 않는 날짜로 가족 앨범을 조회하면 400을 반환한다")
    void 존재하지_않는_날짜로_가족_앨범을_조회하면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get(BASE_PATH + "/family").param("date", "2026-13-40"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(albumCalendarService);
    }

    @Test
    @DisplayName("캘린더 조회 월이 0이면 400을 반환한다")
    void 캘린더_조회_월이_0이면_400을_반환한다() throws Exception {
        // when & then
        mockMvc().perform(get(BASE_PATH).param("year", "2026").param("month", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(albumCalendarService);
    }
}
