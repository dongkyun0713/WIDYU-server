package com.widyu.fcm.event.album.listener;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.fcm.event.album.dto.AlbumLikedEvent;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlbumNotificationListener {

    private static final String ALBUM_DEFAULT_IMAGE = "album.png";
    static final String GUARDIAN_ALBUM_DETAIL = "widyu-care://albums/";
    static final String GUARDIAN_ALBUM_LIST = "widyu-care://albums";

    private final FcmService fcmService;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MemberRepository memberRepository;
    private final AlbumViewRepository albumViewRepository;
    private final AlbumRepository albumRepository;
    private final AlbumUnlockRepository albumUnlockRepository;

    @EventListener
    @Transactional
    public void handleAlbumCreated(AlbumCreatedEvent event) {
        Member author = memberRepository.findById(event.authorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        String albumId = event.albumId().toString();
        NotificationCopy uploadCopy = NotificationCopy.of(NotificationType.ALBUM_UPLOAD_COMPLETE, "A01", Map.of());
        String uploadDeepLink = null;
        if (author.getType() == MemberType.GUARDIAN) {
            uploadDeepLink = guardianAlbumLink(albumId);
        }
        send(author.getId(), FcmSendDto.of(NotificationType.ALBUM_UPLOAD_COMPLETE, uploadCopy,
                albumId, uploadDeepLink, null, null, ALBUM_DEFAULT_IMAGE));

        if (author.getType() == MemberType.SENIOR) {
            Optional<Long> familyId = seniorProfileRepository.findFamilyIdByMemberId(author.getId());
            if (familyId.isEmpty()) {
                return;
            }
            for (FamilyMembership membership : familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId.get())) {
                Member guardian = membership.getGuardian();
                NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_CREATED, "A02-C",
                        Map.of("작성자 이름", author.getName()));
                send(guardian.getId(), FcmSendDto.of(NotificationType.ALBUM_CREATED, copy, albumId,
                        guardianAlbumLink(albumId), author.getId(), author.getId(), author.getProfileImage()));
            }
            return;
        }

        Optional<Long> familyId = familyMembershipRepository.findFamilyIdByGuardianId(author.getId());
        if (familyId.isEmpty()) {
            return;
        }
        List<SeniorProfile> seniors = seniorProfileRepository.findAllByFamilyIdWithMember(familyId.get());
        for (SeniorProfile senior : seniors) {
            NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_CREATED, "A02-S",
                    Map.of("작성자 이름", author.getName()));
            send(senior.getMember().getId(), FcmSendDto.of(NotificationType.ALBUM_CREATED, copy,
                    albumId, null, author.getId(), null, author.getProfileImage()));
        }
    }

    @EventListener
    @Transactional
    public void handleAlbumViewed(AlbumViewedEvent event) {
        Member viewer = memberRepository.findById(event.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        Album album = albumRepository.findByIdAndStatusWithCollections(event.albumId(), Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));
        Member writer = album.getMember();
        if (writer.getType() != MemberType.GUARDIAN || viewer.getType() != MemberType.SENIOR) {
            return;
        }
        long total = albumViewRepository.countTotalAlbumsByParent(writer.getId());
        long viewed = albumViewRepository.countViewedAlbumsByGuardianAndParent(viewer.getId(), writer.getId());
        if (total == 0 || viewed != total) {
            return;
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_ALL_VIEWED, "A07",
                Map.of("시니어 이름", viewer.getName()));
        send(writer.getId(), FcmSendDto.of(NotificationType.ALBUM_ALL_VIEWED, copy, null,
                GUARDIAN_ALBUM_LIST, viewer.getId(), viewer.getId(), ALBUM_DEFAULT_IMAGE));
    }

    @Scheduled(cron = "0 0 10 * * *")
    @Transactional
    public void checkInactiveUsersAndSendNotification() {
        LocalDateTime now = LocalDateTime.now();
        checkAndNotifyInactiveUsers(3, now);
        checkAndNotifyInactiveUsers(5, now);
        checkAndNotifyInactiveUsers(7, now);
    }

    private void checkAndNotifyInactiveUsers(int days, LocalDateTime now) {
        LocalDateTime cutoffDate = now.minusDays(days);
        List<Member> allMembers = memberRepository.findAll();

        for (Member member : allMembers) {
            Optional<LocalDateTime> lastUploadDate = albumRepository.findLastUploadDateByMember(member, Status.ACTIVE);

            boolean shouldNotify = false;
            if (lastUploadDate.isEmpty()) {
                if (member.getCreatedAt().isBefore(cutoffDate)) {
                    shouldNotify = true;
                }
            } else {
                if (lastUploadDate.get().isBefore(cutoffDate)) {
                    shouldNotify = true;
                }
            }

            if (shouldNotify) {
                sendInactivityNotificationToSeniors(member, days);
            }
        }
    }

    private void sendInactivityNotificationToSeniors(Member member, int days) {
        FamilyMembership myMembership = familyMembershipRepository.findByGuardianId(member.getId())
                .orElse(null);
        if (myMembership == null) {
            return;
        }

        List<SeniorProfile> seniors = seniorProfileRepository
                .findAllByFamilyIdWithMember(myMembership.getFamily().getId());
        if (seniors.isEmpty()) {
            return;
        }

        String message = member.getName() + "님, " + days + "일 간 소식이 뜸했어요. 새로운 근황을 전하는 건 어떨까요?";

        for (SeniorProfile senior : seniors) {
            FcmSendDto dto = new FcmSendDto(message, "새로운 소식을 공유해보세요.", FcmCategory.ALBUM, "", ALBUM_DEFAULT_IMAGE)
                    .withRelatedMember(member.getId());
            fcmService.sendMessageToUser(senior.getMember().getId(), dto);
        }
    }

    @EventListener
    @Transactional
    public void handleAlbumCommented(AlbumCommentedEvent event) {
        Member commenter = memberRepository.findById(event.commenterMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        Member writer = memberRepository.findById(event.albumAuthorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        if (commenter.getId().equals(writer.getId())) {
            return;
        }
        NotificationType type = NotificationType.ALBUM_COMMENTED;
        String code = "A03";
        if (event.parentCommentId() != null) {
            type = NotificationType.ALBUM_REPLIED;
            code = "A04";
        }
        NotificationCopy copy = NotificationCopy.of(type, code, Map.of("작성자 이름", commenter.getName()));
        String albumId = event.albumId().toString();
        String commentId = event.commentId().toString();
        String deepLink = null;
        Long seniorId = null;
        if (writer.getType() == MemberType.GUARDIAN) {
            deepLink = guardianAlbumLink(albumId) + "/comments/" + commentId;
            if (commenter.getType() == MemberType.SENIOR) {
                seniorId = commenter.getId();
            }
        }
        FcmSendDto dto = FcmSendDto.of(type, copy, albumId, deepLink,
                commenter.getId(), seniorId, commenter.getProfileImage())
                .withData(Map.of("commentId", commentId));
        send(writer.getId(), dto);
    }

    @EventListener
    @Transactional
    public void handleAlbumLiked(AlbumLikedEvent event) {
        Member liker = memberRepository.findById(event.likerMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        Member writer = memberRepository.findById(event.albumAuthorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        if (liker.getId().equals(writer.getId())) {
            return;
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_LIKED, "A05",
                Map.of("누른 사람 이름", liker.getName()));
        String albumId = event.albumId().toString();
        String deepLink = null;
        if (writer.getType() == MemberType.GUARDIAN) {
            deepLink = guardianAlbumLink(albumId);
        }
        send(writer.getId(), FcmSendDto.of(NotificationType.ALBUM_LIKED, copy, albumId,
                deepLink, liker.getId(), null, liker.getProfileImage()));
    }

    @EventListener
    @Transactional
    public void handleAlbumUnlocked(AlbumUnlockedEvent event) {
        Member senior = memberRepository.findById(event.parentMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_PARENT_MEMBER_NOT_FOUND));
        Album album = albumRepository.findByIdWithMember(event.albumId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));
        Member writer = album.getMember();
        int remaining = Math.toIntExact(albumUnlockRepository.countRemainingLockedByWriterAndSenior(
                writer.getId(), senior.getId(), album.getId()));
        String code = "A06-L";
        if (remaining == 0) {
            code = "A06-Z";
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.ALBUM_UNLOCKED, code,
                unlockCopyValues(senior.getName(), remaining));
        send(writer.getId(), FcmSendDto.of(NotificationType.ALBUM_UNLOCKED, copy,
                album.getId().toString(), guardianAlbumLink(album.getId().toString()),
                senior.getId(), senior.getId(), senior.getProfileImage())
                .withSeniorUnlockDetails(senior.getName(), remaining));
    }

    private Map<String, String> unlockCopyValues(String seniorName, int remaining) {
        if (seniorName == null || seniorName.isBlank()) {
            return Map.of("남은 개수", Integer.toString(remaining));
        }
        return Map.of("시니어 이름", seniorName, "남은 개수", Integer.toString(remaining));
    }

    private String guardianAlbumLink(String albumId) {
        return GUARDIAN_ALBUM_DETAIL + albumId;
    }

    private void send(Long recipientId, FcmSendDto dto) {
        fcmService.sendMessageToUser(recipientId, dto);
    }
}
