package com.widyu.heart.application;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.heart.HeartRateEmergency;
import com.widyu.heart.HeartRateEvent;
import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import com.widyu.heart.dto.request.HeartRateSingleRequest;
import com.widyu.heart.repository.HeartRateEmergencyRepository;
import com.widyu.heart.repository.HeartRateEventRepository;
import com.widyu.heart.repository.HeartRateResultRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
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

    /** 측정값 1건의 최신 결과와 이벤트를 저장한다(LLD-0023). */
    @Transactional
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
                HeartRateEvent.of(member, request.heartRate(), request.measuredAt(), status));

        if (isEmergency) {
            heartRateEmergencyRepository.save(HeartRateEmergency.of(
                    member, request.heartRate(), request.measuredAt(), request.location()));
        }

        return result;
    }
}
