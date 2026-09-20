package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.AdminAuditLog;
import com.widyu.admin.dto.response.AdminAuditLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.admin.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminAuditLogRepository auditLogRepository;
    private final AdminIdentityResolver adminIdentityResolver;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AdminAction action, String targetType, Long targetId, String detail) {
        Long adminId = adminIdentityResolver.resolveAdminId();
        String adminName = adminIdentityResolver.resolveAdminName(adminId);
        auditLogRepository.save(AdminAuditLog.of(adminId, adminName, action, targetType, targetId, detail));
    }

    @Transactional(readOnly = true)
    public AdminPageResponse<AdminAuditLogResponse> getLogs(AdminAction action, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("id").descending());
        Page<AdminAuditLogResponse> result;
        if (action != null) {
            result = auditLogRepository.findByActionOrderByIdDesc(action, pageRequest).map(AdminAuditLogResponse::from);
        } else {
            result = auditLogRepository.findAllByOrderByIdDesc(pageRequest).map(AdminAuditLogResponse::from);
        }
        return AdminPageResponse.from(result);
    }
}
