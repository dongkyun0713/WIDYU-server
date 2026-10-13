package com.widyu.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.auth.application.SmsService;
import com.widyu.fcm.application.FcmTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "firebase.config-path=/dev/null",
        "s3.credentials.access-key=test",
        "s3.credentials.secret-key=test",
        "s3.region.statics=ap-northeast-2",
        "s3.bucket-name=test-bucket",
        "coolsms.api-key=test",
        "coolsms.api-secret=test",
        "coolsms.api-url=https://api.coolsms.co.kr",
        "coolsms.from-phone-number=01000000000",
        "coolsms.verification-code-length=6",
        "coolsms.verification-code-ttl=300",
        "coolsms.message-template=인증번호: {code}"
})
@DisplayName("STOMP 브로커 하트비트 설정 테스트")
class WebSocketHeartbeatConfigTest {

    @MockBean private FcmTransport fcmTransport;
    @MockBean private com.widyu.auth.repository.TemporaryMemberRepository temporaryMemberRepository;
    @MockBean private com.widyu.auth.repository.RefreshTokenRepository refreshTokenRepository;
    @MockBean private com.widyu.auth.infrastructure.AuthLimitStore authLimitStore;
    @MockBean private com.widyu.auth.infrastructure.ClientIpResolver clientIpResolver;
    @MockBean private S3Client s3Client;
    @MockBean private SmsService smsService;
    @MockBean private net.bramp.ffmpeg.FFmpeg ffmpeg;
    @MockBean private net.bramp.ffmpeg.FFprobe ffprobe;

    @Autowired private SimpleBrokerMessageHandler simpleBrokerMessageHandler;

    @Test
    @DisplayName("STOMP 브로커를 띄우면 10초 하트비트와 브로커 스케줄러를 쓴다")
    void STOMP_브로커를_띄우면_10초_하트비트와_브로커_스케줄러를_쓴다() {
        // given / when
        long[] heartbeat = simpleBrokerMessageHandler.getHeartbeatValue();

        // then
        assertThat(heartbeat).containsExactly(10_000L, 10_000L);
        assertThat(simpleBrokerMessageHandler.getTaskScheduler()).isNotNull();
    }
}
