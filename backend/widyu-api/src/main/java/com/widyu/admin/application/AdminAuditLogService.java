package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.AdminAuditLog;
import com.widyu.admin.dto.response.AdminAuditLogResponse;
import com.widyu.admin.dto.response.AdminPageResponse;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminAuditLogService {

    private final AdminAuditLogRepository auditLogRepository;
    private final MemberRepository memberRepository;

    /** 호출자 트랜잭션이 실패해도 시도 자체를 남긴다. 로그인·상태 변경처럼 시도 기록이 필요한 경로가 쓴다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(AdminAction action, String targetType, Long targetId, String detail) {
        save(action, targetType, targetId, detail);
    }

    /**
     * 대상 변경과 같은 트랜잭션에 남긴다. 변경이 롤백되면 감사 로그도 함께 사라지므로,
     * 실제로 바뀌지 않은 일이 바뀐 것처럼 기록되지 않는다.
     */
    @Transactional
    public void logInCurrentTransaction(
            AdminAction action, String targetType, Long targetId, String detail) {
        save(action, targetType, targetId, detail);
    }

    private void save(AdminAction action, String targetType, Long targetId, String detail) {
        Long adminId = resolveAdminId();
        String adminName = resolveAdminName(adminId);
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

    private Long resolveAdminId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PrincipalDetails pd) {
            return pd.getMemberId();
        }
        return -1L;
    }

    private String resolveAdminName(Long adminId) {
        if (adminId == null || adminId < 0) return "시스템";
        return memberRepository.findById(adminId)
                .map(Member::getName)
                .orElse("알 수 없음");
    }
}
