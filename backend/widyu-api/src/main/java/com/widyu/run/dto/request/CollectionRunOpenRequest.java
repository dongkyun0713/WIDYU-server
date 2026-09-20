package com.widyu.run.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** 회차 열기(LLD-0045 3절). 기기 배정을 함께 받는다. */
public record CollectionRunOpenRequest(
        @NotNull(message = "대상 회원 ID는 필수입니다.")
        Long subjectMemberId,

        @Size(max = 64, message = "연구 ID는 64자 이하여야 합니다.")
        String studyId,

        @Size(max = 64, message = "참여 ID는 64자 이하여야 합니다.")
        String participationId,

        @Size(max = 64, message = "프로토콜 참조는 64자 이하여야 합니다.")
        String protocolRef,

        @Size(max = 50, message = "동의 판은 50자 이하여야 합니다.")
        String consentVersion,

        @Pattern(regexp = "product|research", message = "수집 모드는 product 또는 research여야 합니다.")
        String collectionMode,

        Long startedAtMs,

        @Valid
        RetentionRequest retention,

        @Valid
        List<DeviceAssignRequest> devices
) {

    public static CollectionRunOpenRequest of(
            Long subjectMemberId,
            String studyId,
            String participationId,
            String protocolRef,
            String consentVersion,
            String collectionMode,
            Long startedAtMs,
            RetentionRequest retention,
            List<DeviceAssignRequest> devices
    ) {
        return new CollectionRunOpenRequest(subjectMemberId, studyId, participationId, protocolRef,
                consentVersion, collectionMode, startedAtMs, retention, devices);
    }

    /**
     * 보존 정보(형식서 §6). 통째로 null을 허용한다 — 실증 기간에는 보존 날짜가 없어도 회차를 연다.
     * 값 집합은 미정이라 문자열로 받는다.
     */
    public record RetentionRequest(
            @Size(max = 20, message = "보존 정책은 20자 이하여야 합니다.")
            String dataPolicy,
            LocalDate identifiedUntil,
            LocalDate pseudonymizedAt,
            LocalDate researchUntil
    ) {

        public static RetentionRequest of(
                String dataPolicy,
                LocalDate identifiedUntil,
                LocalDate pseudonymizedAt,
                LocalDate researchUntil
        ) {
            return new RetentionRequest(dataPolicy, identifiedUntil, pseudonymizedAt, researchUntil);
        }
    }
}
