package com.widyu.heart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartRatePersistenceService 단위 테스트")
class HeartRatePersistenceServiceTest {

    @Mock private HeartRateResultRepository heartRateResultRepository;
    @Mock private HeartRateEventRepository heartRateEventRepository;
    @Mock private HeartRateEmergencyRepository heartRateEmergencyRepository;
    @Mock private MemberRepository memberRepository;

    @InjectMocks
    private HeartRatePersistenceService heartRatePersistenceService;

    @Test
    @DisplayName("정상 단건 판정을 저장하면 Result와 Event만 저장한다")
    void 정상_단건_판정을_저장하면_Result와_Event만_저장한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(78, measuredAt, "서울시", "UNKNOWN");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));

        // when
        HeartRateResult result = heartRatePersistenceService.saveMeasurement(
                memberId,
                request,
                HeartRateStatus.NORMAL,
                false
        );

        // then
        assertThat(result.getMemberId()).isEqualTo(memberId);
        assertThat(result.getStatus()).isEqualTo(HeartRateStatus.NORMAL);
        assertThat(result.getHeartRate()).isEqualTo(78);
        assertThat(result.getMeasuredAt()).isEqualTo(measuredAt);
        then(heartRateResultRepository).should().save(any(HeartRateResult.class));
        then(heartRateEventRepository).should().save(any(HeartRateEvent.class));
        then(heartRateEmergencyRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("긴급 단건 판정을 저장하면 해당 심박수로 Emergency를 저장한다")
    void 긴급_단건_판정을_저장하면_해당_심박수로_Emergency를_저장한다() {
        // given
        Long memberId = 1L;
        LocalDateTime measuredAt = LocalDateTime.of(2026, 9, 8, 10, 0);
        HeartRateSingleRequest request = HeartRateSingleRequest.of(180, measuredAt, "서울시", "UNKNOWN");
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
        ArgumentCaptor<HeartRateEmergency> emergencyCaptor = ArgumentCaptor.forClass(HeartRateEmergency.class);

        // when
        heartRatePersistenceService.saveMeasurement(
                memberId,
                request,
                HeartRateStatus.EMERGENCY,
                true
        );

        // then
        then(heartRateEmergencyRepository).should().save(emergencyCaptor.capture());
        HeartRateEmergency emergency = emergencyCaptor.getValue();
        assertThat(emergency.getHeartRate()).isEqualTo(180);
        assertThat(emergency.getMeasuredAt()).isEqualTo(measuredAt);
        assertThat(emergency.getLocation()).isEqualTo("서울시");
    }
}
