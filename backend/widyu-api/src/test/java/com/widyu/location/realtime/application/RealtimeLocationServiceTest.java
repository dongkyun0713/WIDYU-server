package com.widyu.location.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.location.SeniorLocation;
import com.widyu.location.access.LocationAccessPath;
import com.widyu.location.access.application.LocationAccessLogService;
import com.widyu.location.raw.application.LocationFixService;
import com.widyu.location.realtime.dto.LocationPoint;
import com.widyu.location.realtime.dto.LocationUpdateRequest;
import com.widyu.location.realtime.dto.LocationUpdateResponse;
import com.widyu.location.realtime.dto.StayInfo;
import com.widyu.location.realtime.event.SeniorLocationUpdatedEvent;
import com.widyu.location.realtime.repository.SeniorLocationRepository;
import com.widyu.location.parentlocation.repository.ParentLocationRepository;
import com.widyu.member.Family;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("RealtimeLocationService 예외 처리 단위 테스트")
class RealtimeLocationServiceTest {

    @Mock private SeniorLocationRepository seniorLocationRepository;
    @Mock private FamilyMembershipRepository familyMembershipRepository;
    @Mock private SeniorProfileRepository seniorProfileRepository;
    @Mock private ParentLocationRepository parentLocationRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private ListOperations<String, Object> listOperations;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SafeZoneAlertService safeZoneAlertService;
    @Mock private LocationFixService locationFixService;
    @Mock private LocationAccessLogService locationAccessLogService;
    // 실제 제약(memberId @NotNull)을 그대로 태운다. 목이면 검증이 통째로 비어 버린다.
    @Spy private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @InjectMocks
    private RealtimeLocationService realtimeLocationService;

    private static final String RAW_FIX_PAYLOAD = """
            {"v":2,"memberId":1,"device_id":"ph-9c1","session_id":"s-2026","seq":5120,
             "lat":37.5551,"lon":126.9707,"accuracy_m":8.5,"speed_mps":0.9,"provider":"fused",
             "is_mock":false,"ts_ms":1760000000123,"reason":"move"}""";

    @Test
    @DisplayName("인증 회원과 요청 회원이 다르면 FORBIDDEN 예외를 던지고 위치를 저장하지 않는다")
    void 인증_회원과_요청_회원이_다르면_예외가_발생한다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(2L, 37.5, 127.0, null);

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.updateAndBroadcast(request, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("본인의 위치만 업데이트할 수 있습니다.");
        then(seniorLocationRepository).should(never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("존재하지 않는 시니어 위치 업데이트 시 BAD_REQUEST 예외를 던진다")
    void 존재하지_않는_시니어_위치_업데이트_시_예외가_발생한다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(1L, 37.5, 127.0, null);
        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.updateAndBroadcast(request, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BAD_REQUEST)
                .hasMessageContaining("존재하지 않는 시니어입니다.");
    }

    @Test
    @DisplayName("위치를 업데이트하면 최신 위치와 이동 경로를 저장하고 보호자 topic으로 브로드캐스트한다")
    void 위치를_업데이트하면_저장하고_브로드캐스트한다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(1L, 37.5, 127.0, null);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());

        // when
        LocationUpdateResponse response = realtimeLocationService.updateAndBroadcast(request, 1L);

        // then
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.latitude()).isEqualTo(37.5);
        assertThat(response.longitude()).isEqualTo(127.0);
        then(seniorLocationRepository).should().save(any(SeniorLocation.class));
        then(eventPublisher).should().publishEvent(any(SeniorLocationUpdatedEvent.class));
        then(listOperations).should().leftPush(eq("location:trail:1"), any(LocationPoint.class));
        then(valueOperations).should().set(eq("location:stay:1"), any(StayInfo.class), eq(86400L), eq(TimeUnit.SECONDS));
        then(safeZoneAlertService).should().handleSafeZoneTransition(1L, null, null);
        then(messagingTemplate).should().convertAndSend(eq("/topic/location/senior/1"), any(LocationUpdateResponse.class));
    }

    @Test
    @DisplayName("위치 갱신 트랜잭션이 롤백되면 체류 정보를 저장하지 않는다")
    void 위치_갱신_트랜잭션이_롤백되면_체류_정보를_저장하지_않는다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(1L, 37.5, 127.0, null);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());
        TransactionSynchronizationManager.initSynchronization();
        try {
            realtimeLocationService.updateAndBroadcast(request, 1L);

            // when
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            // then
            then(valueOperations).should(never()).set(eq("location:stay:1"), any(), eq(86400L), eq(TimeUnit.SECONDS));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("위치 갱신 트랜잭션이 커밋되면 체류 정보를 저장한다")
    void 위치_갱신_트랜잭션이_커밋되면_체류_정보를_저장한다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(1L, 37.5, 127.0, null);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());
        TransactionSynchronizationManager.initSynchronization();
        try {
            realtimeLocationService.updateAndBroadcast(request, 1L);
            then(valueOperations).should(never()).set(eq("location:stay:1"), any(), eq(86400L), eq(TimeUnit.SECONDS));

            // when
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            // then
            then(valueOperations).should().set(eq("location:stay:1"), any(StayInfo.class), eq(86400L), eq(TimeUnit.SECONDS));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("커밋 후 체류 정보 저장이 실패해도 예외를 전달하지 않는다")
    void 커밋_후_체류_정보_저장이_실패해도_예외를_전달하지_않는다() {
        // given
        LocationUpdateRequest request = LocationUpdateRequest.of(1L, 37.5, 127.0, null);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());
        willThrow(new IllegalStateException("Redis 연결 실패"))
                .given(valueOperations).set(eq("location:stay:1"), any(StayInfo.class), eq(86400L), eq(TimeUnit.SECONDS));
        TransactionSynchronizationManager.initSynchronization();
        try {
            realtimeLocationService.updateAndBroadcast(request, 1L);

            // when & then
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("연결되지 않은 보호자가 마지막 위치 조회 시 FORBIDDEN 예외를 던진다")
    void 연결되지_않은_보호자_마지막_위치_조회_시_예외가_발생한다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.getLastLocation(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("해당 시니어의 위치를 조회할 권한이 없습니다.");
    }

    @Test
    @DisplayName("최근 위치와 체류 정보가 모두 없으면 NOT_FOUND 예외를 던진다")
    void 최근_위치와_체류_정보가_모두_없으면_예외가_발생한다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:2")).willReturn(null);
        given(seniorLocationRepository.findBySeniorId(2L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.getLastLocation(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOT_FOUND)
                .hasMessageContaining("최근 위치 정보가 없습니다.");
    }

    @Test
    @DisplayName("집 밖의 체류 위치이면 외출 중으로 반환한다")
    void 집_밖의_체류위치이면_외출중으로_반환한다() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1"))
                .willReturn(StayInfo.of(37.5, 127.0, null, null));

        // when
        Boolean isOuting = realtimeLocationService.getOutingStatus(1L);

        // then
        assertThat(isOuting).isTrue();
    }

    @Test
    @DisplayName("연결되지 않은 보호자가 이동 경로 조회 시 FORBIDDEN 예외를 던진다")
    void 연결되지_않은_보호자_이동_경로_조회_시_예외가_발생한다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.getLocationTrail(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN)
                .hasMessageContaining("해당 시니어의 위치를 조회할 권한이 없습니다.");
    }

    @Test
    @DisplayName("v2 위치 페이로드를 보내면 기존 실시간 경로를 돌리고 원본 저장을 요청한다")
    void v2_위치_페이로드를_보내면_실시간_경로를_돌리고_원본_저장을_요청한다() {
        // given
        byte[] payload = RAW_FIX_PAYLOAD.getBytes(UTF_8);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());

        // when
        LocationUpdateResponse response = realtimeLocationService.updateAndBroadcast(payload, 1L);

        // then
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.latitude()).isEqualTo(37.5551);
        assertThat(response.longitude()).isEqualTo(126.9707);
        then(seniorLocationRepository).should().save(any(SeniorLocation.class));
        then(listOperations).should().leftPush(eq("location:trail:1"), any(LocationPoint.class));
        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/location/senior/1"), any(LocationUpdateResponse.class));
        then(locationFixService).should().validate(any(LocationUpdateRequest.class));
        then(locationFixService).should()
                .store(eq(1L), any(LocationUpdateRequest.class), eq(payload), anyLong(), anyLong());
    }

    @Test
    @DisplayName("기존 4필드 페이로드를 보내면 원본을 저장하지 않고 기존대로 브로드캐스트한다")
    void 기존_4필드_페이로드를_보내면_원본을_저장하지_않는다() {
        // given
        byte[] payload = "{\"memberId\":1,\"latitude\":37.5,\"longitude\":127.0}".getBytes(UTF_8);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());

        // when
        LocationUpdateResponse response = realtimeLocationService.updateAndBroadcast(payload, 1L);

        // then
        assertThat(response.latitude()).isEqualTo(37.5);
        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/location/senior/1"), any(LocationUpdateResponse.class));
        then(locationFixService).should(never())
                .store(any(), any(LocationUpdateRequest.class), any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("원본 저장이 실패해도 위치 응답을 그대로 돌려준다")
    void 원본_저장이_실패해도_위치_응답을_그대로_돌려준다() {
        // given
        byte[] payload = RAW_FIX_PAYLOAD.getBytes(UTF_8);
        Member member = member(1L);
        SeniorProfile seniorProfile = seniorProfile(10L, member);

        given(seniorProfileRepository.findByMemberId(1L)).willReturn(Optional.of(seniorProfile));
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.leftPush(eq("location:trail:1"), any(LocationPoint.class))).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:1")).willReturn(null);
        given(parentLocationRepository.findAllByMember(member)).willReturn(java.util.List.of());
        willThrow(new IllegalStateException("DB 장애"))
                .given(locationFixService)
                .store(eq(1L), any(LocationUpdateRequest.class), eq(payload), anyLong(), anyLong());

        // when
        LocationUpdateResponse response = realtimeLocationService.updateAndBroadcast(payload, 1L);

        // then
        assertThat(response.memberId()).isEqualTo(1L);
        then(messagingTemplate).should()
                .convertAndSend(eq("/topic/location/senior/1"), any(LocationUpdateResponse.class));
    }

    @Test
    @DisplayName("본문을 읽을 수 없으면 LOCATION_FIX_INVALID 예외가 발생한다")
    void 본문을_읽을_수_없으면_예외가_발생한다() {
        // given
        byte[] payload = "not-json".getBytes(UTF_8);

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.updateAndBroadcast(payload, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LOCATION_FIX_INVALID);
    }

    @Test
    @DisplayName("보호자가 마지막 위치를 조회하면 권한 검증 뒤 REST_LAST 열람 기록을 남긴다")
    void 마지막_위치_조회는_REST_LAST_기록을_남긴다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:2")).willReturn(StayInfo.of(37.5, 127.0, null, null));
        given(seniorLocationRepository.findBySeniorId(2L)).willReturn(Optional.empty());

        // when
        LocationUpdateResponse response = realtimeLocationService.getLastLocation(2L, 1L);

        // then
        assertThat(response.memberId()).isEqualTo(2L);
        then(locationAccessLogService).should().record(1L, 2L, LocationAccessPath.REST_LAST);
    }

    @Test
    @DisplayName("권한이 없어 위치 조회가 막히면 열람 기록을 남기지 않는다")
    void 권한이_없으면_열람_기록을_남기지_않는다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> realtimeLocationService.getLastLocation(2L, 1L))
                .isInstanceOf(BusinessException.class);
        then(locationAccessLogService).should(never())
                .record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(LocationAccessPath.class));
    }

    @Test
    @DisplayName("열람 기록이 예외를 던져도 위치 응답은 그대로 돌려준다")
    void 열람_기록이_실패해도_위치_응답을_돌려준다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("location:stay:2")).willReturn(StayInfo.of(37.5, 127.0, null, null));
        given(seniorLocationRepository.findBySeniorId(2L)).willReturn(Optional.empty());
        willThrow(new IllegalStateException("DB 장애"))
                .given(locationAccessLogService).record(1L, 2L, LocationAccessPath.REST_LAST);

        // when
        LocationUpdateResponse response = realtimeLocationService.getLastLocation(2L, 1L);

        // then
        assertThat(response.memberId()).isEqualTo(2L);
        assertThat(response.latitude()).isEqualTo(37.5);
    }

    @Test
    @DisplayName("보호자가 이동 경로를 조회하면 REST_TRAIL 열람 기록을 남긴다")
    void 이동_경로_조회는_REST_TRAIL_기록을_남긴다() {
        // given
        SeniorProfile seniorProfile = seniorProfile(10L, member(2L));
        given(seniorProfileRepository.findByMemberId(2L)).willReturn(Optional.of(seniorProfile));
        given(familyMembershipRepository.existsByGuardianIdAndSeniorProfileId(1L, 10L)).willReturn(true);
        given(redisTemplate.opsForList()).willReturn(listOperations);
        given(listOperations.range("location:trail:2", 0, -1)).willReturn(java.util.List.of());

        // when
        realtimeLocationService.getLocationTrail(2L, 1L);

        // then
        then(locationAccessLogService).should().record(1L, 2L, LocationAccessPath.REST_TRAIL);
    }

    @Test
    @DisplayName("보호자가 가족 시니어 목록을 조회하면 목록에 담긴 시니어마다 REST_FAMILY 기록을 남긴다")
    void 가족_목록_조회는_시니어마다_REST_FAMILY_기록을_남긴다() {
        // given
        Member seniorMember = member(2L);
        SeniorProfile seniorProfile = seniorProfile(10L, seniorMember);
        FamilyMembership membership =
                FamilyMembership.createMembership(seniorProfile.getFamily(), member(1L));
        given(familyMembershipRepository.findByGuardianId(1L)).willReturn(Optional.of(membership));
        given(seniorProfileRepository.findAllByFamilyIdWithMember(org.mockito.ArgumentMatchers.any()))
                .willReturn(java.util.List.of(seniorProfile));
        given(seniorLocationRepository.findAllById(java.util.List.of(2L)))
                .willReturn(java.util.List.of(SeniorLocation.of(2L, 37.5, 127.0)));

        // when
        realtimeLocationService.getTrackedSeniors(1L);

        // then
        then(locationAccessLogService).should().record(1L, 2L, LocationAccessPath.REST_FAMILY);
    }

    private Member member(Long id) {
        Member member = Member.createMember(MemberType.SENIOR, "부모님", "01011112222");
        ReflectionTestUtils.setField(member, "id", id);
        return member;
    }

    private SeniorProfile seniorProfile(Long id, Member member) {
        SeniorProfile seniorProfile = SeniorProfile.createSeniorProfile(
                member, Family.createFamily("ABC123"), "서울시", "INV1234", LocalDate.of(1950, 1, 1));
        ReflectionTestUtils.setField(seniorProfile, "id", id);
        ReflectionTestUtils.setField(member, "seniorProfile", seniorProfile);
        return seniorProfile;
    }
}
