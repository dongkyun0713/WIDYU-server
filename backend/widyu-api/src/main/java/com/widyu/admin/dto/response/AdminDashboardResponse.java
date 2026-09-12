package com.widyu.admin.dto.response;

import java.util.List;

public record AdminDashboardResponse(
        // 회원
        long totalMembers,
        long seniorCount,
        long guardianCount,
        long todayNewMembers,
        long yesterdayNewMembers,

        // 가족 & 앨범
        long totalFamilyConnections,
        long todayAlbums,
        long weekAlbums,
        long monthAlbums,
        long processingAlbums,

        // 결제
        long todayPaymentTotal,
        long monthPaymentTotal,
        long pendingPayments,

        // 심박 응급
        long heartEmergencyCount,
        long todayHeartEmergencies,

        // 주간 추이 (최근 7일)
        List<DailyCount> weeklyMemberTrend,
        List<DailyCount> weeklyAlbumTrend
) {
    public static AdminDashboardResponse of(
            long totalMembers, long seniorCount, long guardianCount, long todayNewMembers, long yesterdayNewMembers,
            long totalFamilyConnections, long todayAlbums, long weekAlbums, long monthAlbums, long processingAlbums,
            long todayPaymentTotal, long monthPaymentTotal, long pendingPayments,
            long heartEmergencyCount, long todayHeartEmergencies,
            List<DailyCount> weeklyMemberTrend, List<DailyCount> weeklyAlbumTrend
    ) {
        return new AdminDashboardResponse(totalMembers, seniorCount, guardianCount, todayNewMembers, yesterdayNewMembers,
                totalFamilyConnections, todayAlbums, weekAlbums, monthAlbums, processingAlbums,
                todayPaymentTotal, monthPaymentTotal, pendingPayments, heartEmergencyCount, todayHeartEmergencies,
                weeklyMemberTrend, weeklyAlbumTrend);
    }

    public record DailyCount(String date, long count) {}
}
