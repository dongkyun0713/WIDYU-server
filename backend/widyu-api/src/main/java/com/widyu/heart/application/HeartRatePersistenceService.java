package com.widyu.heart.application;

import com.widyu.fcm.event.heart.dto.HeartRateEmergencyEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import io.micrometer.core.annotation.Timed;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HeartRatePersistenceService {

    private final HeartRateResultRepository heartRateResultRepository;
    private final HeartRateEventRepository heartRateEventRepository;
    private final HeartRateEmergencyRepository heartRateEmergencyRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 측정값 1건의 최신 결과와 이벤트를 저장한다(LLD-0023). */
    @Transactional
    @Timed(value = "heart.persistence", extraTags = {"path", "single"})
    public HeartRateResult saveMeasurement(
            Long memberId,
            HeartRateSingleRequest request,
            HeartRateStatus status,
            boolean isEmergency
    ) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        HeartRateResult result = HeartRateResult.of(
                memberId, status, request.heartRate(), request.measuredAt());
        heartRateResultRepository.save(result);

        heartRateEventRepository.save(
                // 단건 경로에는 정확도도 배치 원문도 없다.
                HeartRateEvent.of(member, request.heartRate(), request.measuredAt(), status, null, null));

        if (isEmergency) {
            heartRateEmergencyRepository.save(HeartRateEmergency.of(
                    member, request.heartRate(), request.measuredAt(), request.location()));
            // 단건 경로는 판정 기록을 남기지 않는다. 가리킬 판정이 없으면 도달 사실도 채우지 않는다.
            eventPublisher.publishEvent(new HeartRateEmergencyEvent(memberId, null));
        }

        return result;
    }

    /**
     * 배치 샘플 1건을 저장한다(LLD-0047 5절 6단계). 저장이 판정에 앞서므로 상태가 {@code UNKNOWN}이어도
     * 그대로 저장한다. 위급이면 기존 단건 경로와 같은 이벤트를 발행해 알림 정책을 공유한다.
     * 배치에는 주소가 없어 {@code location}은 null이다(ADR-0031 후속).
     *
     * <p>{@code decisionId}는 이 위급을 낳은 판정 기록을 가리킨다. 알림이 실제로 나갔다는 사실을
     * 그 행에 채우려고 이벤트에 싣는다(LLD-0053 5.2). 판정 기록이 없으면 null이다.
     */
    @Transactional
    @Timed(value = "heart.persistence", extraTags = {"path", "batch"})
    public void saveBatchSample(
            Member member,
            Integer heartRate,
            LocalDateTime measuredAt,
            HeartRateStatus status,
            boolean isEmergency,
            String accuracy,
            String batchId,
            String decisionId
    ) {
        heartRateEventRepository.save(
                HeartRateEvent.of(member, heartRate, measuredAt, status, accuracy, batchId));

        if (isEmergency) {
            heartRateEmergencyRepository.save(
                    HeartRateEmergency.of(member, heartRate, measuredAt, null));
            eventPublisher.publishEvent(new HeartRateEmergencyEvent(member.getId(), decisionId));
        }
    }

    /** 배치의 마지막 샘플로 최신값(Redis)을 갱신한다. */
    @Transactional
    public void updateLatestResult(
            Long memberId, HeartRateStatus status, Integer heartRate, LocalDateTime measuredAt) {
        heartRateResultRepository.save(HeartRateResult.of(memberId, status, heartRate, measuredAt));
    }
}
