package com.widyu.location.access.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.consent.ConsentKey;
import com.widyu.consent.application.ConsentService;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.global.properties.LocationAccessProperties;
import com.widyu.location.access.LocationAccessLog;
import com.widyu.location.access.LocationAccessPath;
import com.widyu.location.access.dto.response.LocationAccessLogResponse;
import com.widyu.location.access.repository.LocationAccessLogRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("LocationAccessLogService 위치 열람 기록·통보 단위 테스트")
class LocationAccessLogServiceTest {

    private static final Long VIEWER_ID = 55L;
    private static final Long SENIOR_ID = 7L;
    private static final int COOLDOWN_MIN = 10;

    @Mock private LocationAccessLogRepository locationAccessLogRepository;
    @Mock private ConsentService consentService;
    @Mock private com.widyu.member.repository.MemberRepository memberRepository;
    @Mock private FcmService fcmService;

    private LocationAccessLogService locationAccessLogService;

    @BeforeEach
    void setUp() {
        locationAccessLogService = new LocationAccessLogService(
                locationAccessLogRepository,
                consentService,
                memberRepository,
                fcmService,
                new LocationAccessProperties(COOLDOWN_MIN, "0 0 9 * * *"));
    }

    @Test
    @DisplayName("시니어가 자기 위치를 조회하면 기록도 통보도 남기지 않는다")
    void 본인_조회는_기록하지_않는다() {
        // given & when
        locationAccessLogService.record(SENIOR_ID, SENIOR_ID, LocationAccessPath.REST_LAST);

        // then
        then(locationAccessLogRepository).should(never()).save(any(LocationAccessLog.class));
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
    }

    @Test
    @DisplayName("즉시 통보 시니어를 보호자가 처음 조회하면 FCM 한 건을 보내고 통보 시각을 남긴다")
    void 즉시_모드_첫_조회는_FCM을_보내고_통보_시각을_남긴다() {
        // given
        givenSaveReturnsArgument();
        given(consentService.isGranted(SENIOR_ID, ConsentKey.LOCATION_NOTICE_BATCHED)).willReturn(false);
        given(locationAccessLogRepository.existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                eq(SENIOR_ID), eq(VIEWER_ID), any(LocalDateTime.class))).willReturn(false);
        given(memberRepository.findById(VIEWER_ID)).willReturn(Optional.of(guardian()));

        // when
        locationAccessLogService.record(VIEWER_ID, SENIOR_ID, LocationAccessPath.REST_LAST);

        // then
        ArgumentCaptor<FcmSendDto> sent = ArgumentCaptor.forClass(FcmSendDto.class);
        then(fcmService).should().sendMessageToUser(eq(SENIOR_ID), sent.capture());
        assertThat(sent.getValue().fcmCategory()).isEqualTo(FcmCategory.LOCATION_NOTICE);
        assertThat(sent.getValue().title()).isEqualTo("위치 조회 알림");
        assertThat(sent.getValue().content()).isEqualTo("김보호님이 내 위치를 확인했어요");
        assertThat(sent.getValue().relatedMemberId()).isEqualTo(VIEWER_ID);
        assertThat(sent.getValue().emergency()).isFalse();
        assertThat(savedLog().getNotifiedAt()).isNotNull();
        assertThat(savedLog().getPath()).isEqualTo(LocationAccessPath.REST_LAST);
    }

    @Test
    @DisplayName("기록할 때 시니어 회원 행을 먼저 잠근 뒤에 쿨다운을 확인한다")
    void 시니어_행을_잠근_뒤_쿨다운을_확인한다() {
        // given
        givenSaveReturnsArgument();
        given(consentService.isGranted(SENIOR_ID, ConsentKey.LOCATION_NOTICE_BATCHED)).willReturn(false);
        given(locationAccessLogRepository.existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                eq(SENIOR_ID), eq(VIEWER_ID), any(LocalDateTime.class))).willReturn(false);
        given(memberRepository.findById(VIEWER_ID)).willReturn(Optional.of(guardian()));

        // when
        locationAccessLogService.record(VIEWER_ID, SENIOR_ID, LocationAccessPath.REST_LAST);

        // then
        InOrder inOrder = Mockito.inOrder(memberRepository, locationAccessLogRepository);
        inOrder.verify(memberRepository).findByIdForUpdate(SENIOR_ID);
        inOrder.verify(locationAccessLogRepository)
                .existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                        eq(SENIOR_ID), eq(VIEWER_ID), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("본인 조회는 잠금도 걸지 않는다")
    void 본인_조회는_잠금도_걸지_않는다() {
        // given & when
        locationAccessLogService.record(SENIOR_ID, SENIOR_ID, LocationAccessPath.REST_LAST);

        // then
        then(memberRepository).should(never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("쿨다운 안에 같은 보호자가 다시 조회하면 FCM을 보내지 않고 통보 시각을 비워 둔다")
    void 쿨다운_안_재조회는_통보하지_않고_요약_대상으로_남긴다() {
        // given
        givenSaveReturnsArgument();
        given(consentService.isGranted(SENIOR_ID, ConsentKey.LOCATION_NOTICE_BATCHED)).willReturn(false);
        given(locationAccessLogRepository.existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                eq(SENIOR_ID), eq(VIEWER_ID), any(LocalDateTime.class))).willReturn(true);

        // when
        locationAccessLogService.record(VIEWER_ID, SENIOR_ID, LocationAccessPath.REST_LAST);

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
        assertThat(savedLog().getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("쿨다운이 지난 뒤 다시 조회하면 쿨다운 시작 시각을 기준으로 판단해 FCM을 다시 보낸다")
    void 쿨다운이_지난_뒤_재조회하면_다시_통보한다() {
        // given
        givenSaveReturnsArgument();
        given(consentService.isGranted(SENIOR_ID, ConsentKey.LOCATION_NOTICE_BATCHED)).willReturn(false);
        ArgumentCaptor<LocalDateTime> cooldownFrom = ArgumentCaptor.forClass(LocalDateTime.class);
        given(locationAccessLogRepository.existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                eq(SENIOR_ID), eq(VIEWER_ID), cooldownFrom.capture())).willReturn(false);
        given(memberRepository.findById(VIEWER_ID)).willReturn(Optional.of(guardian()));

        // when
        locationAccessLogService.record(VIEWER_ID, SENIOR_ID, LocationAccessPath.REST_TRAIL);

        // then
        then(fcmService).should().sendMessageToUser(eq(SENIOR_ID), any(FcmSendDto.class));
        assertThat(cooldownFrom.getValue())
                .isBefore(LocalDateTime.now().minusMinutes(COOLDOWN_MIN - 1L));
        assertThat(savedLog().getNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("모아서 통보에 동의한 시니어를 조회하면 기록만 남기고 FCM을 보내지 않는다")
    void 모아서_모드는_조회_시점에_통보하지_않는다() {
        // given
        givenSaveReturnsArgument();
        given(consentService.isGranted(SENIOR_ID, ConsentKey.LOCATION_NOTICE_BATCHED)).willReturn(true);

        // when
        locationAccessLogService.record(VIEWER_ID, SENIOR_ID, LocationAccessPath.WS_SUBSCRIBE);

        // then
        then(fcmService).should(never()).sendMessageToUser(anyLong(), any(FcmSendDto.class));
        then(locationAccessLogRepository).should(never())
                .existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
                        anyLong(), anyLong(), any(LocalDateTime.class));
        assertThat(savedLog().getNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("기간을 주지 않고 조회하면 요청한 본인의 최근 30일 기록을 최근순으로 돌려준다")
    void 기간을_주지_않으면_최근_30일을_최근순으로_돌려준다() {
        // given
        LocationAccessLog accessLog = accessLog(LocationAccessPath.HOME_OUTING);
        given(locationAccessLogRepository.findBySeniorMemberIdAndAccessedAtBetween(
                eq(SENIOR_ID), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(accessLog)));
        given(memberRepository.findAllById(List.of(VIEWER_ID))).willReturn(List.of(guardian()));

        // when
        Page<LocationAccessLogResponse> result =
                locationAccessLogService.myLogs(SENIOR_ID, null, null, 0, 30);

        // then
        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(locationAccessLogRepository).should().findBySeniorMemberIdAndAccessedAtBetween(
                eq(SENIOR_ID), from.capture(), any(LocalDateTime.class), pageable.capture());
        assertThat(from.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        assertThat(pageable.getValue()).isEqualTo(
                PageRequest.of(0, 30, org.springframework.data.domain.Sort.by("accessedAt").descending()));
        assertThat(result.getContent()).singleElement()
                .satisfies(response -> {
                    assertThat(response.viewerMemberId()).isEqualTo(VIEWER_ID);
                    assertThat(response.viewerName()).isEqualTo("김보호");
                    assertThat(response.path()).isEqualTo(LocationAccessPath.HOME_OUTING);
                });
    }

    @Test
    @DisplayName("다른 회원의 기록을 섞어 달라고 할 입구가 없어 조회 대상은 언제나 요청한 본인이다")
    void 조회_대상은_언제나_요청한_본인이다() {
        // given
        given(locationAccessLogRepository.findBySeniorMemberIdAndAccessedAtBetween(
                eq(SENIOR_ID), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        // when
        Page<LocationAccessLogResponse> result =
                locationAccessLogService.myLogs(SENIOR_ID, null, null, 0, 30);

        // then
        assertThat(result.getContent()).isEmpty();
        then(locationAccessLogRepository).should().findBySeniorMemberIdAndAccessedAtBetween(
                eq(SENIOR_ID), any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class));
        then(memberRepository).should(never()).findAllById(any());
    }

    @Test
    @DisplayName("열람 기록 응답을 만들면 좌표에 해당하는 필드가 하나도 없다")
    void 응답에_좌표가_없다() {
        // given
        List<String> fieldNames = Arrays.stream(LocationAccessLogResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // when & then
        assertThat(fieldNames).containsExactly(
                "id", "viewerMemberId", "viewerName", "path", "accessedAt", "notifiedAt");
        assertThat(fieldNames).noneMatch(name ->
                name.toLowerCase().contains("lat") || name.toLowerCase().contains("lon"));
    }

    private void givenSaveReturnsArgument() {
        given(locationAccessLogRepository.save(any(LocationAccessLog.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private LocationAccessLog savedLog() {
        ArgumentCaptor<LocationAccessLog> saved = ArgumentCaptor.forClass(LocationAccessLog.class);
        then(locationAccessLogRepository).should().save(saved.capture());
        return saved.getValue();
    }

    private LocationAccessLog accessLog(LocationAccessPath path) {
        LocationAccessLog accessLog =
                LocationAccessLog.of(VIEWER_ID, SENIOR_ID, path, LocalDateTime.now());
        ReflectionTestUtils.setField(accessLog, "id", 912L);
        return accessLog;
    }

    private Member guardian() {
        Member member = Member.createMember(MemberType.GUARDIAN, "김보호", "01011112222");
        ReflectionTestUtils.setField(member, "id", VIEWER_ID);
        return member;
    }
}
