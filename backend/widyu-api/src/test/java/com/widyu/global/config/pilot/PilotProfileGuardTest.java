package com.widyu.global.config.pilot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.widyu.global.properties.PilotProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("실증 프로파일 격리 게이트 단위 테스트")
@ExtendWith(MockitoExtension.class)
class PilotProfileGuardTest {

    private PilotProfileGuard guard(MockEnvironment environment, PilotProperties properties) {
        return new PilotProfileGuard(environment, properties);
    }

    @Test
    @DisplayName("운영 프로파일과 함께 활성화하면 예외가 발생한다")
    void 운영_프로파일과_함께_활성화하면_예외가_발생한다() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("pilot", "dev");
        PilotProperties properties = new PilotProperties(true, "widyu-pilot");

        // when & then
        assertThatThrownBy(() -> guard(environment, properties).verifyIsolatedPilotEnvironment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev");
    }

    @Test
    @DisplayName("격리 확인이 false면 예외가 발생한다")
    void 격리_확인이_false면_예외가_발생한다() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("pilot");
        PilotProperties properties = new PilotProperties(false, "widyu-pilot");

        // when & then
        assertThatThrownBy(() -> guard(environment, properties).verifyIsolatedPilotEnvironment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isolation-confirmed");
    }

    @Test
    @DisplayName("실증 Firebase 프로젝트가 비어 있으면 예외가 발생한다")
    void 실증_Firebase_프로젝트가_비어_있으면_예외가_발생한다() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("pilot");
        PilotProperties properties = new PilotProperties(true, "  ");

        // when & then
        assertThatThrownBy(() -> guard(environment, properties).verifyIsolatedPilotEnvironment())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("firebase-project-id");
    }

    @Test
    @DisplayName("격리 확인과 Firebase 프로젝트가 설정되면 통과한다")
    void 격리_확인과_Firebase_프로젝트가_설정되면_통과한다() {
        // given
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("pilot");
        PilotProperties properties = new PilotProperties(true, "widyu-pilot");

        // when & then
        assertThatCode(() -> guard(environment, properties).verifyIsolatedPilotEnvironment())
                .doesNotThrowAnyException();
    }
}
