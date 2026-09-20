package com.widyu.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자가 개인정보를 조회·변경한 접속기록. 추가 전용이며 요청 본문·헤더는 담지 않는다.
 * 업무 행위 기록인 {@link AdminAuditLog}과 역할이 다르다(ADR-0036, LLD-0057).
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "admin_access_log", indexes = {
        @Index(name = "idx_admin_access_log_admin_time", columnList = "admin_id, accessed_at"),
        @Index(name = "idx_admin_access_log_time", columnList = "accessed_at")
})
public class AdminAccessLog {

    private static final int QUERY_MAX_LENGTH = 500;
    private static final int USER_AGENT_MAX_LENGTH = 200;
    private static final int PATH_MAX_LENGTH = 255;
    private static final int TARGET_REF_MAX_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "admin_access_log_id")
    private Long id;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(nullable = false, length = 50)
    private String adminName;

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = PATH_MAX_LENGTH)
    private String path;

    @Column(name = "query", length = QUERY_MAX_LENGTH)
    private String query;

    private Long targetMemberId;

    @Column(length = TARGET_REF_MAX_LENGTH)
    private String targetRef;

    @Column(nullable = false)
    private int status;

    @Column(length = 45)
    private String clientIp;

    @Column(length = USER_AGENT_MAX_LENGTH)
    private String userAgent;

    @Column(name = "accessed_at", nullable = false, updatable = false)
    private LocalDateTime accessedAt;

    @Builder(access = AccessLevel.PRIVATE)
    private AdminAccessLog(Long adminId, String adminName, String method, String path, String query,
                           Long targetMemberId, String targetRef, int status, String clientIp,
                           String userAgent, LocalDateTime accessedAt) {
        this.adminId = adminId;
        this.adminName = adminName;
        this.method = method;
        this.path = path;
        this.query = query;
        this.targetMemberId = targetMemberId;
        this.targetRef = targetRef;
        this.status = status;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.accessedAt = accessedAt;
    }

    public static AdminAccessLog of(Long adminId, String adminName, String method, String path, String query,
                                    Long targetMemberId, String targetRef, int status, String clientIp,
                                    String userAgent, LocalDateTime accessedAt) {
        return AdminAccessLog.builder()
                .adminId(adminId)
                .adminName(adminName)
                .method(method)
                .path(truncate(path, PATH_MAX_LENGTH))
                .query(truncate(query, QUERY_MAX_LENGTH))
                .targetMemberId(targetMemberId)
                .targetRef(truncate(targetRef, TARGET_REF_MAX_LENGTH))
                .status(status)
                .clientIp(clientIp)
                .userAgent(truncate(userAgent, USER_AGENT_MAX_LENGTH))
                .accessedAt(accessedAt)
                .build();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
