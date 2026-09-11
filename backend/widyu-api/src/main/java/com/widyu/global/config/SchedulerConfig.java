package com.widyu.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

// 테스트 컨텍스트에서 스케줄러가 목 의존성을 호출해 간헐 실패를 일으키지 않도록 프로퍼티로 끌 수 있게 한다
// 실증(pilot)에서는 자동 스케줄러(복약·걷기·건강일정·결제 복구·심박 정리 등)를 일괄 제외한다
@Configuration
@EnableScheduling
@Profile("!pilot")
@ConditionalOnProperty(name = "widyu.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig {
}
