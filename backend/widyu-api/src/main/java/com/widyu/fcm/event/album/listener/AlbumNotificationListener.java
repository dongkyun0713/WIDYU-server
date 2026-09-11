package com.widyu.fcm.event.album.listener;

import com.widyu.fcm.event.album.dto.AlbumViewedEvent;
import com.widyu.fcm.event.album.dto.AlbumCommentedEvent;
import com.widyu.fcm.event.album.dto.AlbumLikedEvent;
import com.widyu.fcm.event.album.dto.AlbumUnlockedEvent;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.album.Album;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.album.repository.AlbumRepository;
import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.event.album.dto.AlbumCreatedEvent;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.global.entity.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@org.springframework.context.annotation.Profile("!pilot") // 실증 제외: 자동 알림
@Component
@RequiredArgsConstructor
public class AlbumNotificationListener {

    private static final String ALBUM_DEFAULT_IMAGE = "album.png";

    private final FcmService fcmService;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MemberRepository memberRepository;
    private final AlbumViewRepository albumViewRepository;
    private final AlbumRepository albumRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAlbumCreated(AlbumCreatedEvent event) {
        Member author = memberRepository.findById(event.authorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));

        sendNotificationToSpecificMember(
                event.authorId(),
                "앨범 업로드가 완료되었어요!",
                "업로드한 앨범을 확인해보세요."
        );

        String title = author.getName() + "님이 새로운 소식을 전했어요!";
        String content = "새로운 앨범을 확인해보세요.";

        sendNotificationToFamilyMembers(event.authorId(), title, content, author.getProfileImage());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAlbumViewed(AlbumViewedEvent event) {
        Member viewer = memberRepository.findById(event.memberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));

        Album album = albumRepository.findByIdAndStatusWithCollections(event.albumId(), Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        if (hasViewedAllAlbums(event.memberId(), album)) {
            Member albumWriter = album.getMember();
            String title = viewer.getName() + "님이 " + albumWriter.getName() + "님의 모든 소식을 확인했어요!";
            String content = "새로운 소식을 공유해보세요.";
            sendNotificationToSpecificMember(albumWriter.getId(), title, content);
        }
    }

    private void sendNotificationToFamilyMembers(Long memberId, String title, String content, String image) {
        Optional<Long> seniorFamilyId = seniorProfileRepository.findFamilyIdByMemberId(memberId);
        if (seniorFamilyId.isPresent()) {
            Long familyId = seniorFamilyId.get();
            List<FamilyMembership> memberships = familyMembershipRepository.findAllByFamilyIdWithGuardian(familyId);
            for (FamilyMembership membership : memberships) {
                FcmSendDto dto = new FcmSendDto(title, content, FcmCategory.ALBUM, "", image);
                sendNotificationSafely(membership.getGuardian().getId(), dto);
            }
        } else {
            familyMembershipRepository.findFamilyIdByGuardianId(memberId).ifPresent(familyId -> {
                List<SeniorProfile> seniors = seniorProfileRepository
                        .findAllByFamilyIdWithMember(familyId);
                for (SeniorProfile senior : seniors) {
                    FcmSendDto dto = new FcmSendDto(title, content, FcmCategory.ALBUM, "", image);
                    sendNotificationSafely(senior.getMember().getId(), dto);
                }
            });
        }
    }

    private void sendNotificationToSpecificMember(Long memberId, String title, String content) {
        FcmSendDto dto = new FcmSendDto(title, content, FcmCategory.ALBUM, "", ALBUM_DEFAULT_IMAGE);
        sendNotificationSafely(memberId, dto);
    }

    private void sendNotificationSafely(Long memberId, FcmSendDto dto) {
        try {
            fcmService.sendMessageToUser(memberId, dto);
        } catch (RuntimeException e) {
            log.warn("앨범 FCM 알림 전송 실패: memberId={}, category={}, error={}",
                    memberId, dto.fcmCategory(), e.getMessage());
        }
    }

    private boolean hasViewedAllAlbums(Long viewerId, Album album) {
        Member writer = album.getMember();
        long totalCount = albumRepository.countByMemberId(writer.getId());
        long viewedCount = albumViewRepository.countViewedAlbumsByGuardianAndParent(viewerId, writer.getId());

        log.info("작성자: {}, 전체: {}, 본 개수: {}", writer.getName(), totalCount, viewedCount);
        return viewedCount == totalCount && totalCount > 0;
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
            FcmSendDto dto = new FcmSendDto(message, "새로운 소식을 공유해보세요.", FcmCategory.ALBUM, "", ALBUM_DEFAULT_IMAGE);
            try {
                fcmService.sendMessageToUser(senior.getMember().getId(), dto);
                log.info("{}일 비활성 알림 전송 완료: {} -> {}", days, member.getName(), senior.getMember().getName());
            } catch (Exception e) {
                log.error("{}일 비활성 알림 전송 실패: {} -> {}", days, member.getName(), senior.getMember().getName(), e);
            }
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAlbumCommented(AlbumCommentedEvent event) {
        Member commenter = memberRepository.findById(event.commenterMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        Member albumAuthor = memberRepository.findById(event.albumAuthorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));

        if (event.commenterMemberId().equals(event.albumAuthorId())) {
            return;
        }

        FcmSendDto dto = new FcmSendDto(
                commenter.getName() + "님이 회원님의 게시물에 댓글을 남겼어요!",
                "답글을 달아주세요.",
                FcmCategory.ALBUM,
                "",
                commenter.getProfileImage()
        );
        sendNotificationSafely(albumAuthor.getId(), dto);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAlbumLiked(AlbumLikedEvent event) {
        Member liker = memberRepository.findById(event.likerMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));
        Member albumAuthor = memberRepository.findById(event.albumAuthorId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_MEMBER_NOT_FOUND));

        if (event.likerMemberId().equals(event.albumAuthorId())) {
            return;
        }

        FcmSendDto dto = new FcmSendDto(
                liker.getName() + "님이 회원님의 게시물을 좋아합니다!",
                "게시물을 확인해보세요.",
                FcmCategory.ALBUM,
                "",
                liker.getProfileImage()
        );
        sendNotificationSafely(albumAuthor.getId(), dto);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAlbumUnlocked(AlbumUnlockedEvent event) {
        Member parentMember = memberRepository.findById(event.parentMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_PARENT_MEMBER_NOT_FOUND));

        Album album = albumRepository.findByIdWithMember(event.albumId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ALBUM_NOT_FOUND));

        FcmSendDto dto = new FcmSendDto(
                parentMember.getName() + "님이 회원님의 게시물을 잠금해제했어요.",
                "새로운 소식을 확인해보세요.",
                FcmCategory.ALBUM,
                "",
                parentMember.getProfileImage()
        );
        sendNotificationSafely(album.getMember().getId(), dto);
    }
}
