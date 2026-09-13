package com.widyu.admin.dto.response;

import com.widyu.global.entity.Status;
import com.widyu.member.MemberRole;
import com.widyu.member.MemberType;
import com.widyu.pay.PaymentStatus;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

public record AdminMemberDetailFullResponse(
        Long id,
        String name,
        String phoneNumber,
        MemberType type,
        MemberRole role,
        Status status,
        LocalDateTime createdAt,

        FamilyInfo familyInfo,
        long activeFcmTokens,
        List<RecentAlbum> recentAlbums,
        List<RecentPayment> recentPayments,
        long heartEmergencyCount
) {
    public static AdminMemberDetailFullResponse of(Long id, String name, String phoneNumber, MemberType type,
                                                   MemberRole role, Status status, LocalDateTime createdAt,
                                                   FamilyInfo familyInfo, long activeFcmTokens,
                                                   List<RecentAlbum> recentAlbums, List<RecentPayment> recentPayments,
                                                   long heartEmergencyCount) {
        return new AdminMemberDetailFullResponse(id, name, phoneNumber, type, role, status, createdAt, familyInfo,
                activeFcmTokens, recentAlbums, recentPayments, heartEmergencyCount);
    }

    public record FamilyInfo(
            String familyCode,
            // 시니어 전용
            String inviteCode,
            String address,
            Long points,
            // 보호자 전용
            Boolean isLeader,
            Boolean isRepresentative,
            String nickname,
            LocalDateTime connectedAt
    ) {}

    public record RecentAlbum(
            Long id,
            String thumbnail,
            Status status,
            LocalDateTime createdAt
    ) {
        public static RecentAlbum from(com.widyu.album.Album album) {
            String thumbnail = null;
            if (!album.getThumbnailUrls().isEmpty()) {
                thumbnail = album.getThumbnailUrls().get(0);
            }
            return new RecentAlbum(album.getId(), thumbnail, album.getStatus(), album.getCreatedAt());
        }
    }

    public record RecentPayment(
            Long id,
            String orderName,
            int amount,
            PaymentStatus status,
            ZonedDateTime approvedAt
    ) {}
}
