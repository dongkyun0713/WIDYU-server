package com.widyu.run;

import com.widyu.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차에 기기를 배정한 기록(LLD-0045 4절).
 * 배정 구간 {@code [assigned_at_ms, unassigned_at_ms)}가 자료를 어느 참가자에게 붙일지 정한다.
 */
@Entity
@Getter
@Table(
    name = "run_device_assignment",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_run_device_assignment_id", columnNames = "assignment_id"),
    indexes = @Index(
        name = "idx_run_device_assignment_device", columnList = "device_id, unassigned_at_ms")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunDeviceAssignment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "assignment_id", nullable = false, length = 40)
    private String assignmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private CollectionRun run;

    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(name = "role", nullable = false, length = 20)
    private String role;

    @Column(name = "wear_site", length = 30)
    private String wearSite;

    @Column(name = "assigned_at_ms", nullable = false)
    private Long assignedAtMs;

    @Column(name = "unassigned_at_ms")
    private Long unassignedAtMs;

    @Builder
    private RunDeviceAssignment(
            String assignmentId,
            CollectionRun run,
            String deviceId,
            String role,
            String wearSite,
            Long assignedAtMs
    ) {
        this.assignmentId = assignmentId;
        this.run = run;
        this.deviceId = deviceId;
        this.role = role;
        this.wearSite = wearSite;
        this.assignedAtMs = assignedAtMs;
    }

    public void unassign(Long unassignedAtMs) {
        this.unassignedAtMs = unassignedAtMs;
    }

    public boolean isAssigned() {
        return this.unassignedAtMs == null;
    }
}
