package com.widyu.decision;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 낙상 판정의 입력 근거와 결과(LLD-0051 4절). */
@Entity
@Getter
@Table(
    name = "decision_record",
    uniqueConstraints = @UniqueConstraint(name = "uk_decision_record_decision_id", columnNames = "decision_id"),
    indexes = {
        @Index(name = "idx_decision_record_run_time", columnList = "run_id, decision_at_ms"),
        @Index(name = "idx_decision_record_member_time", columnList = "member_id, decision_at_ms"),
        @Index(name = "idx_decision_record_trigger_batch", columnList = "trigger_batch_id")
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DecisionRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_record_id")
    private Long id;

    @Column(name = "decision_id", nullable = false, length = 40)
    private String decisionId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "stream_ids_used", nullable = false, columnDefinition = "TEXT")
    private String streamIdsUsed;

    @Column(name = "decision_at_ms", nullable = false)
    private Long decisionAtMs;

    @Column(name = "decision_output", nullable = false, length = 32)
    private String decisionOutput;

    @Column(name = "decider_id", nullable = false, length = 64)
    private String deciderId;

    @Column(name = "decider_version", nullable = false, length = 64)
    private String deciderVersion;

    @Column(name = "input_cutoff_ms", nullable = false)
    private Long inputCutoffMs;

    @Column(name = "feature_support_end_ms", nullable = false)
    private Long featureSupportEndMs;

    @Column(name = "model_available_at_server_max_ms", nullable = false)
    private Long modelAvailableAtServerMaxMs;

    @Column(name = "window_start_ms", nullable = false)
    private Long windowStartMs;

    @Column(name = "window_end_ms", nullable = false)
    private Long windowEndMs;

    @Column(name = "alert_id", length = 40)
    private String alertId;

    @Column(name = "alert_at_ms")
    private Long alertAtMs;

    @Column(name = "severity", length = 20)
    private String severity;

    @Column(name = "trigger_path", length = 40)
    private String triggerPath;

    @Column(name = "alert_delivered", nullable = false)
    private Boolean alertDelivered;

    @Column(name = "trigger_batch_id", nullable = false, length = 26)
    private String triggerBatchId;

    @Builder
    private DecisionRecord(
            String decisionId, Long memberId, String runId, String streamIdsUsed, Long decisionAtMs,
            String decisionOutput, String deciderId, String deciderVersion, Long inputCutoffMs,
            Long featureSupportEndMs, Long modelAvailableAtServerMaxMs, Long windowStartMs, Long windowEndMs,
            String severity, String triggerPath, String triggerBatchId) {
        this.decisionId = decisionId;
        this.memberId = memberId;
        this.runId = runId;
        this.streamIdsUsed = streamIdsUsed;
        this.decisionAtMs = decisionAtMs;
        this.decisionOutput = decisionOutput;
        this.deciderId = deciderId;
        this.deciderVersion = deciderVersion;
        this.inputCutoffMs = inputCutoffMs;
        this.featureSupportEndMs = featureSupportEndMs;
        this.modelAvailableAtServerMaxMs = modelAvailableAtServerMaxMs;
        this.windowStartMs = windowStartMs;
        this.windowEndMs = windowEndMs;
        this.severity = severity;
        this.triggerPath = triggerPath;
        this.alertDelivered = false;
        this.triggerBatchId = triggerBatchId;
    }
}
