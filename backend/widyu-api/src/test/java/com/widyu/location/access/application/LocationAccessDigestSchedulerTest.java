package com.widyu.location.access.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationAccessDigestScheduler 위치 열람 요약 통보 단위 테스트")
class LocationAccessDigestSchedulerTest {

    @Mock private LocationAccessLogRepository locationAccessLogRepository;
    @Mock private FcmService fcmService;

    @InjectMocks
    private LocationAccessDigestScheduler locationAccessDigestScheduler;

    @Test
    @DisplayName("미통보 행을 선점하면 선점한 행만 세어 시니어마다 FCM 한 건을 보낸다")
    void 선점한_행을_세어_시니어마다_한_건씩_통보한다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of(7L, 8L));
        given(locationAccessLogRepository.claimUnnotified(eq(7L), any(LocalDateTime.class))).willReturn(3);
        given(locationAccessLogRepository.claimUnnotified(eq(8L), any(LocalDateTime.class))).willReturn(1);
        given(locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(
                eq(7L), any(LocalDateTime.class)))
                .willReturn(List.of(
                        accessLog(1L, 7L, 55L, LocationAccessPath.REST_LAST),
                        accessLog(2L, 7L, 55L, LocationAccessPath.WS_SUBSCRIBE),
                        accessLog(3L, 7L, 66L, LocationAccessPath.HOME_OUTING)));
        given(locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(
                eq(8L), any(LocalDateTime.class)))
                .willReturn(List.of(accessLog(4L, 8L, 55L, LocationAccessPath.REST_FAMILY)));

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        ArgumentCaptor<FcmSendDto> sent = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(7L), sent.capture());
        then(fcmService).should().sendMessageToUser(eq(8L), any(FcmSendDto.class));
        assertThat(sent.getValue().fcmCategory()).isEqualTo(FcmCategory.LOCATION_NOTICE);
        assertThat(sent.getValue().content()).isEqualTo("지난 기간 보호자 2명이 위치를 3회 확인했어요");
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 선점해 갱신 건수가 0이면 FCM을 보내지 않는다")
    void 선점에_실패하면_통보하지_않는다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of(7L));
        given(locationAccessLogRepository.claimUnnotified(eq(7L), any(LocalDateTime.class))).willReturn(0);

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
        then(locationAccessLogRepository).should(never())
                .findBySeniorMemberIdAndNotifiedAt(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("통보는 선점에 성공한 뒤에만 나가므로 한 시니어의 실패가 다른 시니어를 막지 않는다")
    void 한_시니어_실패가_다른_시니어를_막지_않는다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of(7L, 8L));
        given(locationAccessLogRepository.claimUnnotified(anyLong(), any(LocalDateTime.class))).willReturn(1);
        given(locationAccessLogRepository.findBySeniorMemberIdAndNotifiedAt(
                anyLong(), any(LocalDateTime.class)))
                .willReturn(List.of(accessLog(1L, 7L, 55L, LocationAccessPath.REST_LAST)));
        willThrow(new IllegalStateException("FCM 장애"))
                .given(fcmService).sendMessageToUser(eq(7L), any(FcmSendDto.class));

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(fcmService).should().sendMessageToUser(eq(8L), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("미통보 기록이 없으면 선점도 FCM도 하지 않는다")
    void 미통보_기록이_없으면_아무것도_하지_않는다() {
        // given
        given(locationAccessLogRepository.findSeniorIdsWithUnnotified()).willReturn(List.of());

        // when
        locationAccessDigestScheduler.sendDigest();

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
        then(locationAccessLogRepository).should(never())
                .claimUnnotified(anyLong(), any(LocalDateTime.class));
    }

    private LocationAccessLog accessLog(Long id, Long seniorId, Long viewerId, LocationAccessPath path) {
        LocationAccessLog accessLog =
                LocationAccessLog.of(viewerId, seniorId, path, LocalDateTime.now());
        ReflectionTestUtils.setField(accessLog, "id", id);
        return accessLog;
    }
}
