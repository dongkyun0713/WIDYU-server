package com.widyu.location.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.widyu.auth.infrastructure.AuthRedisFixture;
import com.widyu.fcm.event.safezone.dto.SafeZoneEnterEvent;
import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.global.config.RedisConfig;
import com.widyu.location.access.application.LocationAccessLogService;
import com.widyu.location.parentlocation.repository.ParentLocationRepository;
import com.widyu.location.raw.application.LocationFixService;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.member.Family;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.parentlocation.LocationType;
import com.widyu.parentlocation.ParentLocation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 체류 정보가 실제 Redis를 왕복한 뒤에도 안심구역 전이를 감지하는지 확인한다(LLD-0097). mock은 저장한 객체를 그대로
 * 돌려줘 타입 정보가 사라지는 문제를 드러내지 못한다. Redis는 테스트마다 격리 프로세스를 띄우고(CI는
 * scripts/ci/prepare-redis.sh), 템플릿은 운영 RedisConfig로 만든다. 알림 키 판정도 실제 Lua로 돈다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("체류 정보 실제 Redis 왕복과 안심구역 전이 통합 테스트")
class RealtimeLocationSafeZoneRedisTest {

    private static final Long SENIOR_ID = 1L;
    private static final double HOME_LAT = 37.5665;
    private static final double HOME_LNG = 126.9780;
    private static final double NEAR_HOME_LAT = 37.5666; // 약 11m, 같은 체류
    private static final double AWAY_LAT = 37.5765; // 약 1.1km, 안심구역(75m) 밖

    @Mock private SeniorLocationRepository seniorLocationRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private ParentLocationRepository parentLocationRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private ApplicationEventPublisher locationEventPublisher;
    @Mock private LocationFixService locationFixService;
    @Mock private LocationAccessLogService locationAccessLogService;
    @Mock private MemberRepository memberRepository;
    @Mock private Validator validator;

    @Test
    @DisplayName("안심구역 안에서 밖으로 나갔다 돌아오면 이탈·돌아옴 이벤트를 한 번씩 발행하고 같은 자리 재전송은 체류 시작 시각을 유지한다")
    void 안에서_밖으로_나갔다_돌아오면_이탈_돌아옴_이벤트를_한번씩_발행한다() throws Exception {
        try (AuthRedisFixture redis = new AuthRedisFixture()) {
            // given
            List<Object> safeZoneEvents = new ArrayList<>();
            RealtimeLocationService service = service(redis, safeZoneEvents);
            seniorWithHomeSafeZone();
            LocationUpdateResponse atHome = service.updateAndBroadcast(request(HOME_LAT), SENIOR_ID);

            // when
            LocationUpdateResponse stillHome = service.updateAndBroadcast(request(NEAR_HOME_LAT), SENIOR_ID);
            LocationUpdateResponse away = service.updateAndBroadcast(request(AWAY_LAT), SENIOR_ID);
            LocationUpdateResponse back = service.updateAndBroadcast(request(HOME_LAT), SENIOR_ID);

            // then
            assertThat(safeZoneEvents).containsExactly(
                    new SafeZoneExitEvent(SENIOR_ID), new SafeZoneEnterEvent(SENIOR_ID));
            assertThat(stillHome.stayStartTime()).isEqualTo(atHome.stayStartTime());
            assertThat(stillHome.locationType()).isEqualTo("HOME");
            assertThat(away.locationType()).isNull();
            assertThat(back.locationType()).isEqualTo("HOME");
            assertThat(service.getOutingStatus(SENIOR_ID)).isFalse();
        }
    }

    @Test
    @DisplayName("이탈 갱신 트랜잭션이 롤백되면 체류 정보와 알림 키가 남지 않아 다음 위치 갱신에서 이탈을 다시 감지한다")
    void 이탈_갱신이_롤백되면_다음_갱신에서_이탈을_다시_감지한다() throws Exception {
        try (AuthRedisFixture redis = new AuthRedisFixture()) {
            // given
            List<Object> safeZoneEvents = new ArrayList<>();
            RealtimeLocationService service = service(redis, safeZoneEvents);
            seniorWithHomeSafeZone();
            service.updateAndBroadcast(request(HOME_LAT), SENIOR_ID);
            rolledBack(() -> service.updateAndBroadcast(request(AWAY_LAT), SENIOR_ID));
            Boolean outingAfterRollback = service.getOutingStatus(SENIOR_ID);

            // when
            service.updateAndBroadcast(request(AWAY_LAT), SENIOR_ID);

            // then
            // 첫 이벤트는 롤백된 갱신에서 나왔다(운영에서는 리스너의 outbox 저장도 함께 롤백된다).
            assertThat(outingAfterRollback).isFalse();
            assertThat(safeZoneEvents).containsExactly(
                    new SafeZoneExitEvent(SENIOR_ID), new SafeZoneExitEvent(SENIOR_ID));
        }
    }

    private RealtimeLocationService service(AuthRedisFixture redis, List<Object> safeZoneEvents) {
        RedisTemplate<String, Object> redisTemplate =
                new RedisConfig(null).redisTemplate(redis.connectionFactory());
        SafeZoneAlertService safeZoneAlertService = new SafeZoneAlertService(redisTemplate, safeZoneEvents::add);
        return new RealtimeLocationService(seniorLocationRepository, familyMembershipRepository,
                seniorProfileRepository, parentLocationRepository, messagingTemplate, redisTemplate,
                locationEventPublisher, safeZoneAlertService, locationFixService, locationAccessLogService,
                memberRepository, validator);
    }

    private void seniorWithHomeSafeZone() {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        ReflectionTestUtils.setField(member, "id", SENIOR_ID);
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(member, "seniorProfile", seniorProfile);
        ParentLocation home = ParentLocation.builder()
                .member(member).locationType(LocationType.HOME).placeAddress("서울시")
                .latitude(HOME_LAT).longitude(HOME_LNG).name("집").build();
        given(seniorProfileRepository.findByMemberId(SENIOR_ID)).willReturn(Optional.of(seniorProfile));
        given(parentLocationRepository.findAllByMember(member)).willReturn(List.of(home));
    }

    private static LocationUpdateRequest request(double latitude) {
        return LocationUpdateRequest.of(SENIOR_ID, latitude, HOME_LNG, null);
    }

    /** 트랜잭션 동기화 안에서 실행한 뒤 롤백 완료 콜백만 부른다. 커밋 후 저장(afterCommit)은 실행되지 않는다. */
    private static void rolledBack(Runnable work) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            work.run();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
