package com.widyu.global.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class BuildVersionLogTest {

    @Test
    @DisplayName("배포 커밋을 알림 분석기가 찾는 형식으로 남긴다")
    void 배포_커밋을_알림_분석기가_찾는_형식으로_남긴다() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(BuildVersionLog.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            // when
            new BuildVersionLog("38ea59eb").onReady();

            // then
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("[BUILD] Version=38ea59eb");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
