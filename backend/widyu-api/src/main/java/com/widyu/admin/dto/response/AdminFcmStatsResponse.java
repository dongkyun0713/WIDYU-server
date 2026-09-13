package com.widyu.admin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record AdminFcmStatsResponse(
        long activeTokenCount,
        long inactiveTokenCount,
        long membersWithToken,
        long membersWithoutToken,
        List<InactiveTokenEntry> recentlyDeactivated,
        List<RecentTestSend> recentTestSends
) {
    public static AdminFcmStatsResponse of(long activeTokenCount, long inactiveTokenCount, long membersWithToken,
                                           long membersWithoutToken, List<InactiveTokenEntry> recentlyDeactivated,
                                           List<RecentTestSend> recentTestSends) {
        return new AdminFcmStatsResponse(activeTokenCount, inactiveTokenCount, membersWithToken, membersWithoutToken,
                recentlyDeactivated, recentTestSends);
    }

    public record InactiveTokenEntry(
            Long memberId,
            String memberName,
            String deviceInfo,
            LocalDateTime expiredAt
    ) {}

    public record RecentTestSend(
            String adminName,
            Long targetMemberId,
            String detail,
            LocalDateTime sentAt
    ) {}
}
