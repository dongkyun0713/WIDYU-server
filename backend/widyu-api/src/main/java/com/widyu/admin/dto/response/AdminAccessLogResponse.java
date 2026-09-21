package com.widyu.admin.dto.response;

import com.widyu.admin.AdminAccessLog;
import java.time.LocalDateTime;

public record AdminAccessLogResponse(
        Long id,
        Long adminId,
        String adminName,
        String method,
        String path,
        String query,
        Long targetMemberId,
        String targetRef,
        int status,
        String clientIp,
        String userAgent,
        LocalDateTime accessedAt
) {
    public static AdminAccessLogResponse from(AdminAccessLog log) {
        return new AdminAccessLogResponse(
                log.getId(),
                log.getAdminId(),
                log.getAdminName(),
                log.getMethod(),
                log.getPath(),
                log.getQuery(),
                log.getTargetMemberId(),
                log.getTargetRef(),
                log.getStatus(),
                log.getClientIp(),
                log.getUserAgent(),
                log.getAccessedAt()
        );
    }
}
