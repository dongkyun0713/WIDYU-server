package com.widyu.admin.application;

import com.widyu.admin.AdminAccessLog;
import com.widyu.admin.dto.response.AdminAccessLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.admin.repository.AdminAccessLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAccessLogService {

    private static final String UNAUTHENTICATED_ADMIN_NAME = "인증 전";

    private final AdminAccessLogRepository accessLogRepository;
    private final AdminIdentityResolver adminIdentityResolver;

    /**
     * 요청 하나를 접속기록으로 남긴다. 요청 처리 트랜잭션이 롤백돼도 기록은 남아야 하므로 새 트랜잭션에서 저장한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String method, String path, String query, Long targetMemberId, String targetRef,
                       int status, String clientIp, String userAgent, LocalDateTime accessedAt) {
        Long adminId = adminIdentityResolver.resolveAdminId();
        accessLogRepository.save(AdminAccessLog.of(adminId, resolveAdminName(adminId), method, path, query,
                targetMemberId, targetRef, status, clientIp, userAgent, accessedAt));
    }

    @Transactional(readOnly = true)
    public AdminPageResponse<AdminAccessLogResponse> search(Long adminId, LocalDateTime from, LocalDateTime to,
                                                            int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").descending());
        Page<AdminAccessLogResponse> result = accessLogRepository.search(adminId, from, to, pageRequest)
                .map(AdminAccessLogResponse::from);
        return AdminPageResponse.from(result);
    }

    private String resolveAdminName(Long adminId) {
        if (AdminIdentityResolver.UNAUTHENTICATED_ADMIN_ID.equals(adminId)) {
            return UNAUTHENTICATED_ADMIN_NAME;
        }
        return adminIdentityResolver.resolveAdminName(adminId);
    }
}
