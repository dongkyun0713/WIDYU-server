package com.widyu.fcm.event.album.listener;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.album.repository.GuardianLastActivityRow;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.GuardianDeepLinks;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.fcm.event.album.dto.AlbumLikedEvent;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.fcm.repository.FcmNotificationRepository;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final Set<Integer> INACTIVE_REMINDER_DAYS = Set.of(3, 5, 7);

    private final FcmService fcmService;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MemberRepository memberRepository;
    private final AlbumViewRepository albumViewRepository;
    private final AlbumRepository albumRepository;
    private final AlbumUnlockRepository albumUnlockRepository;
    private final FcmNotificationRepository fcmNotificationRepository;

    @EventListener
    @Transactional
    public void handleAlbumCreated(AlbumCreatedEvent event) {
        Member author = memberRepository.findById(event.authorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        String albumId = event.albumId().toString();
        NotificationCopy uploadCopy = NotificationCopy.of(NotificationType.ALBUM_UPLOAD_COMPLETE, "A01", Map.of());
        String uploadDeepLink = null;
        if (author.getType() == MemberType.GUARDIAN) {
            uploadDeepLink = GuardianDeepLinks.post(albumId);
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
                        GuardianDeepLinks.post(albumId), author.getId(), author.getId(), author.getProfileImage()));
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
                null, viewer.getId(), viewer.getId(), ALBUM_DEFAULT_IMAGE));
    }

    /**
     * 마지막 업로드(없으면 가입) 날짜가 정확히 3·5·7일 전인 보호자의 가족 시니어에게 한 번씩 알린다(LLD-0104).
     * 바깥 트랜잭션을 두지 않아 수신자마다 따로 커밋하고, 한 건의 실패가 다른 알림을 롤백하지 않는다.
     */
    @Scheduled(cron = "0 0 10 * * *")
    public void checkInactiveUsersAndSendNotification() {
        sendInactivityReminders(LocalDate.now());
    }

    void sendInactivityReminders(LocalDate today) {
        List<GuardianLastActivityRow> candidates = albumRepository.findGuardianLastActivityBetween(
                today.minusDays(7).atStartOfDay(), today.minusDays(2).atStartOfDay());
        for (GuardianLastActivityRow guardian : candidates) {
            int days = (int) ChronoUnit.DAYS.between(guardian.lastActivityAt().toLocalDate(), today);
            if (INACTIVE_REMINDER_DAYS.contains(days)) {
                sendInactivityNotificationToSeniors(guardian, days, today);
            }
        }
    }

    private void sendInactivityNotificationToSeniors(GuardianLastActivityRow guardian, int days, LocalDate today) {
        String message = guardian.guardianName() + "님, " + days + "일 간 소식이 뜸했어요. 새로운 근황을 전하는 건 어떨까요?";
        FcmSendDto dto = FcmSendDto.builder()
                .title(message).content("새로운 소식을 공유해보세요.")
                .fcmCategory(FcmCategory.ALBUM).scheme("").image(ALBUM_DEFAULT_IMAGE)
                .relatedMemberId(guardian.guardianId())
                .eventId("inactive:" + guardian.guardianId() + ":" + days + ":" + today)
                .build();
        for (SeniorProfile senior : seniorProfileRepository.findAllByFamilyIdWithMember(guardian.familyId())) {
            Long seniorId = senior.getMember().getId();
            // 같은 날 재실행이면 이미 보낸 알림이다. enqueue는 같은 eventId여도 푸시를 다시 만들므로 여기서 거른다.
            // 동시 실행은 센터 행 UK(recipient, event_id)가 한쪽 트랜잭션을 outbox째 롤백시킨다.
            if (fcmNotificationRepository.findByRecipientMemberIdAndEventId(seniorId, dto.eventId()).isPresent()) {
                continue;
            }
            try {
                fcmService.sendMessageToUser(seniorId, dto);
            } catch (RuntimeException e) {
                log.error("앨범 미업로드 알림 저장 실패 - guardianId: {}, seniorId: {}",
                        guardian.guardianId(), seniorId, e);
            }
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
            deepLink = GuardianDeepLinks.postComment(albumId, commentId);
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
            deepLink = GuardianDeepLinks.post(albumId);
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
                album.getId().toString(), null,
                senior.getId(), senior.getId(), senior.getProfileImage())
                .withSeniorUnlockDetails(senior.getName(), remaining));
    }

    private Map<String, String> unlockCopyValues(String seniorName, int remaining) {
        if (seniorName == null || seniorName.isBlank()) {
            return Map.of("남은 개수", Integer.toString(remaining));
        }
        return Map.of("시니어 이름", seniorName, "남은 개수", Integer.toString(remaining));
    }

    private void send(Long recipientId, FcmSendDto dto) {
        fcmService.sendMessageToUser(recipientId, dto);
    }
}
