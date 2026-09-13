package com.widyu.admin.application;

import com.widyu.admin.AdminAction;
import com.widyu.admin.dto.response.AdminFcmStatsResponse;
import com.widyu.admin.dto.response.AdminFcmStatsResponse.InactiveTokenEntry;
import com.widyu.admin.dto.response.AdminFcmStatsResponse.RecentTestSend;
import com.widyu.admin.repository.AdminAuditLogRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.MemberRole;
import com.widyu.member.repository.MemberRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminFcmStatsService {

    private final MemberFcmTokenRepository fcmTokenRepository;
    private final MemberRepository memberRepository;
    private final AdminAuditLogRepository auditLogRepository;

    @Transactional(readOnly = true)
    public AdminFcmStatsResponse getStats() {
        long activeTokens = fcmTokenRepository.countByActiveTrue();
        long inactiveTokens = fcmTokenRepository.countByActiveFalse();
        long membersWithToken = fcmTokenRepository.countDistinctMembersWithActiveToken();
        long totalMembers = memberRepository.countByRoleNot(MemberRole.ADMIN);
        long membersWithoutToken = totalMembers - membersWithToken;

        List<InactiveTokenEntry> recentlyDeactivated = fcmTokenRepository
                .findTop10InactiveOrderByExpiredAtDesc(PageRequest.of(0, 10))
                .stream()
                .map(t -> new InactiveTokenEntry(
                        t.getMember().getId(),
                        t.getMember().getName(),
                        t.getDeviceInfo(),
                        t.getExpiredAt()
                ))
                .toList();

        List<RecentTestSend> recentTestSends = auditLogRepository
                .findByActionOrderByIdDesc(AdminAction.FCM_TEST_SEND, PageRequest.of(0, 15))
                .getContent()
                .stream()
                .map(log -> new RecentTestSend(
                        log.getAdminName(),
                        log.getTargetId(),
                        log.getDetail(),
                        log.getCreatedAt()
                ))
                .toList();

        return AdminFcmStatsResponse.of(
                activeTokens, inactiveTokens,
                membersWithToken, membersWithoutToken,
                recentlyDeactivated, recentTestSends
        );
    }
}
