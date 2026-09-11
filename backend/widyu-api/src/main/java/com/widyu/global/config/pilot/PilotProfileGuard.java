package com.widyu.global.config.pilot;

import com.widyu.global.properties.PilotProperties;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 실증(pilot) 프로파일이 운영 자원과 격리된 상태로만 기동되도록 강제하는 게이트.
 * 운영 프로파일 혼용 또는 격리 확인 설정 누락 시 애플리케이션 기동을 실패시킨다.
 */
@Slf4j
@Component
@Profile("pilot")
@RequiredArgsConstructor
public class PilotProfileGuard {

    private static final List<String> OPERATIONAL_PROFILES = List.of("local", "dev", "prod", "test");

    private final Environment environment;
    private final PilotProperties pilotProperties;

    @PostConstruct
    void verifyIsolatedPilotEnvironment() {
        List<String> conflicting = Arrays.stream(environment.getActiveProfiles())
                .filter(OPERATIONAL_PROFILES::contains)
                .toList();
        if (!conflicting.isEmpty()) {
            throw new IllegalStateException(
                    "실증(pilot) 프로파일은 운영 프로파일과 함께 활성화할 수 없습니다. 충돌 프로파일: " + conflicting);
        }

        if (!pilotProperties.isolationConfirmed()) {
            throw new IllegalStateException(
                    "실증 격리 확인이 필요합니다. pilot.isolation-confirmed=true 로 격리된 자원 사용을 명시하세요.");
        }

        if (!StringUtils.hasText(pilotProperties.firebaseProjectId())) {
            throw new IllegalStateException(
                    "실증 Firebase 프로젝트가 지정되지 않았습니다. pilot.firebase-project-id 를 설정하세요.");
        }

        log.info("실증(pilot) 프로파일 격리 검증 통과");
    }
}
