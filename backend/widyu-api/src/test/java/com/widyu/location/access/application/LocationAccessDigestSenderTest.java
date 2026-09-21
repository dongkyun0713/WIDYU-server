package com.widyu.location.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.LocationAccessPath;
import com.widyu.location.access.repository.LocationAccessLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationAccessDigestSender 위치 열람 요약 통보 트랜잭션 단위 테스트")
class LocationAccessDigestSenderTest {

    @Mock private LocationAccessLogRepository locationAccessLogRepository;
    @Mock private FcmService fcmService;

    @InjectMocks
    private LocationAccessDigestSender locationAccessDigestSender;

    @Test
    @DisplayName("미통보 행을 선점하면 선점한 행만 세어 FCM 한 건을 등록한다")
    void 선점한_행만_세어_한_건을_통보한다() {
        // given
        LocalDateTime now = LocalDateTime.now();
        given(locationAccessLogRepository.claimUnnotified(7L, now)).willReturn(3);
        given(locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(7L, now))
                .willReturn(List.of(
                        accessLog(7L, 55L, LocationAccessPath.REST_LAST),
                        accessLog(7L, 55L, LocationAccessPath.WS_SUBSCRIBE),
                        accessLog(7L, 66L, LocationAccessPath.HOME_OUTING)));

        // when
        locationAccessDigestSender.send(7L, now);

        // then
        ArgumentCaptor<FcmSendDto> sent = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(7L), sent.capture());
        assertThat(sent.getValue().fcmCategory()).isEqualTo(FcmCategory.LOCATION_NOTICE);
        assertThat(sent.getValue().content()).isEqualTo("지난 기간 보호자 2명이 위치를 3회 확인했어요");
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 선점했으면 FCM을 등록하지 않는다")
    void 선점에_실패하면_통보하지_않는다() {
        // given
        LocalDateTime now = LocalDateTime.now();
        given(locationAccessLogRepository.claimUnnotified(7L, now)).willReturn(0);

        // when
        locationAccessDigestSender.send(7L, now);

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
        then(locationAccessLogRepository).should(never())
                .findBySeniorMemberIdAndNotifiedAt(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("FCM 등록이 실패하면 예외를 전파해 선점 트랜잭션을 롤백한다")
    void FCM_등록이_실패하면_선점도_롤백되도록_예외를_전파한다() {
        // given
        LocalDateTime now = LocalDateTime.now();
        given(locationAccessLogRepository.claimUnnotified(7L, now)).willReturn(1);
        given(locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(7L, now))
                .willReturn(List.of(accessLog(7L, 55L, LocationAccessPath.REST_LAST)));
        willThrow(new IllegalStateException("FCM 장애"))
                .given(fcmService).sendMessageToUser(eq(7L), any(FcmSendDto.class));

        // when & then
        assertThatThrownBy(() -> locationAccessDigestSender.send(7L, now))
                .isInstanceOf(IllegalStateException.class);
    }

    private LocationAccessLog accessLog(Long seniorId, Long viewerId, LocationAccessPath path) {
        return LocationAccessLog.of(viewerId, seniorId, path, LocalDateTime.now());
    }
}
