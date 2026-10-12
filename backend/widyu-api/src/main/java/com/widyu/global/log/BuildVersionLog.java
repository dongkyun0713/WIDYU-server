package com.widyu.global.log;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 실행 중인 배포 커밋을 로그에 남긴다(LLD-0084). 알림 분석기는 오류 직전의 이 줄로 그 시점 소스를 가져온다.
 * Loki 보관 기간(7일)보다 오래 재시작이 없을 때도 찾을 수 있게 하루 한 번 다시 남긴다.
 */
@Slf4j
@Component
public class BuildVersionLog {

    private final String commitSha;

    public BuildVersionLog(@Value("${APP_COMMIT_SHA:unknown}") final String commitSha) {
        this.commitSha = commitSha;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        write();
    }

    @Scheduled(cron = "0 0 4 * * *")
    public void daily() {
        write();
    }

    void write() {
        log.info("[BUILD] Version={}", commitSha);
    }
}
