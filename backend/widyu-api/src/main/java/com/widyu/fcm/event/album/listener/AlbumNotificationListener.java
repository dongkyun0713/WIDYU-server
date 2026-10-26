package com.widyu.fcm.event.album.listener;

import com.widyu.album.Album;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.album.repository.AlbumUnlockRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.NotificationPolicy;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.GuardianDeepLinks;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private static final Set<Long> INACTIVITY_NOTICE_DAYS = Set.of(3L, 5L, 7L);

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
        NotificationCopy uploadCopy = NotificationCopy.of(NotificationPolicy.album("A01"), "A01", Map.of());
        String uploadDeepLink = null;
        if (author.getType() == MemberType.GUARDIAN) {
            uploadDeepLink = GuardianDeepLinks.post(albumId);
        }
        send(author.getId(), FcmSendDto.of(NotificationPolicy.album("A01"), uploadCopy,
                albumId, uploadDeepLink, null, null, author.getProfileImage()));

        if (author.getType() == MemberType.SENIOR) {
            Optional<Long> familyId = seniorProfileRepository.findFamilyIdByMemberId(author.getId());
            if (familyId.isEmpty()) {
                return;
            }
            for (FamilyMembership membership : familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId.get())) {
                Member guardian = membership.getGuardian();
                NotificationCopy copy = NotificationCopy.of(NotificationPolicy.album("A02-S/C"), "A02-C",
                        Map.of("작성자 이름", author.getName()));
                send(guardian.getId(), FcmSendDto.of(NotificationPolicy.album("A02-S/C"), copy, albumId,
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
            NotificationCopy copy = NotificationCopy.of(NotificationPolicy.album("A02-S/C"), "A02-S",
                    Map.of("작성자 이름", author.getName()));
            send(senior.getMember().getId(), FcmSendDto.of(NotificationPolicy.album("A02-S/C"), copy,
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
        NotificationCopy copy = NotificationCopy.of(NotificationPolicy.album("A07"), "A07",
                Map.of("시니어 이름", viewer.getName()));
        send(writer.getId(), FcmSendDto.of(NotificationPolicy.album("A07"), copy, null,
                null, viewer.getId(), viewer.getId(), viewer.getProfileImage()));
    }

    @Scheduled(cron = "0 0 10 * * *")
    @Transactional
    public void checkInactiveUsersAndSendNotification() {
        notifyInactiveGuardians(LocalDate.now());
    }

    /** 마지막 업로드일(없으면 가입일)로부터 정확히 3·5·7일째인 보호자 본인에게 한 번씩 보낸다. */
    void notifyInactiveGuardians(LocalDate today) {
        for (Member member : memberRepository.findAll()) {
            if (member.getType() != MemberType.GUARDIAN) {
                continue;
            }
            LocalDateTime lastActivity = albumRepository.findLastUploadDateByMember(member, Status.ACTIVE)
                    .orElse(member.getCreatedAt());
            long days = ChronoUnit.DAYS.between(lastActivity.toLocalDate(), today);
            if (INACTIVITY_NOTICE_DAYS.contains(days)) {
                sendInactivityNotification(member, days);
            }
        }
    }

    private void sendInactivityNotification(Member guardian, long days) {
        if (familyMembershipRepository.findByGuardianId(guardian.getId()).isEmpty()) {
            return;
        }
        String message = guardian.getName() + "님, " + days + "일 간 소식이 뜸했어요. 새로운 근황을 전하는 건 어떨까요?";
        FcmSendDto dto = new FcmSendDto(message, "새로운 소식을 공유해보세요.", FcmCategory.ALBUM,
                "", guardian.getProfileImage()).withData(Map.of("deepLink", "/album"));
        fcmService.sendMessageToUser(guardian.getId(), dto);
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
        NotificationPolicy type = NotificationPolicy.album("A03");
        String code = "A03";
        if (event.parentCommentId() != null) {
            type = NotificationPolicy.album("A04");
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
        NotificationCopy copy = NotificationCopy.of(NotificationPolicy.album("A05"), "A05",
                Map.of("누른 사람 이름", liker.getName()));
        String albumId = event.albumId().toString();
        String deepLink = null;
        if (writer.getType() == MemberType.GUARDIAN) {
            deepLink = GuardianDeepLinks.post(albumId);
        }
        send(writer.getId(), FcmSendDto.of(NotificationPolicy.album("A05"), copy, albumId,
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
        NotificationCopy copy = NotificationCopy.of(NotificationPolicy.album("A06-L/Z"), code,
                unlockCopyValues(senior.getName(), remaining));
        send(writer.getId(), FcmSendDto.of(NotificationPolicy.album("A06-L/Z"), copy,
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
