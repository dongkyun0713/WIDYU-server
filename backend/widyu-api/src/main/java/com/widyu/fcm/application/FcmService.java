package com.widyu.fcm.application;

import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.request.SendNotificationRequest;
import com.widyu.fcm.dto.response.FcmCategoryResponse;
import com.widyu.fcm.dto.response.FcmNotificationResponses;
import com.widyu.fcm.dto.response.ToastResDto;
import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.album.repository.AlbumViewRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.member.FamilyMembership;
import com.widyu.member.SeniorProfile;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FcmService {

    private final FcmDeliveryProperties deliveryProperties;
    private final FirebaseProperties firebaseProperties;
    private final FcmOutboxService outboxService;
    private final FcmTransport transport;
    private final FcmNotificationRepository fcmNotificationRepository;
    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final AlbumViewRepository albumViewRepository;
    private final MemberRepository memberRepository;
    private final FamilyMembershipRepository familyMembershipRepository;
    private final SeniorProfileRepository seniorProfileRepository;
    private final MemberUtil memberUtil;

    // 카테고리 및 커서 기반 알림 목록 조회
    public FcmNotificationResponses getNotificationsForCurrentUser(String category, Long cursor) {
        Member member = memberUtil.getCurrentMember();

        int pageSize = 10;
        int fetchSize = pageSize + 1;
        Pageable pageable = PageRequest.of(0, fetchSize);

        List<FcmNotification> notifications;

        if ("ALL".equals(category)) {
            notifications = fcmNotificationRepository.findNotificationsWithCursor(
                    member.getId(), cursor, pageable);
        } else {
            try {
                FcmCategory fcmCategory = FcmCategory.valueOf(category);
                notifications = fcmNotificationRepository.findNotificationsByCategoryWithCursor(
                        member.getId(), fcmCategory, cursor, pageable);
            } catch (IllegalArgumentException e) {
                // 잘못된 카테고리인 경우 전체 조회로 처리
                notifications = fcmNotificationRepository.findNotificationsWithCursor(
                        member.getId(), cursor, pageable);
            }
        }

        if (notifications.isEmpty()) {
            return FcmNotificationResponses.empty();
        }

        boolean hasNext = notifications.size() > pageSize;
        List<FcmNotification> pageNotifications = notifications;
        Long nextCursor = null;
        if (hasNext) {
            pageNotifications = notifications.subList(0, pageSize);
            nextCursor = pageNotifications.get(pageSize - 1).getId();
        }

        return FcmNotificationResponses.of(pageNotifications, hasNext, nextCursor);
    }

    // 알림 개별 읽기
    @Transactional
    public String markAsRead(Long notificationId) {
        Member member = memberUtil.getCurrentMember();
        FcmNotification notification = fcmNotificationRepository
                .findByIdAndMemberFcmToken_MemberId(notificationId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FCM_NOTIFICATION_NOT_FOUND));

        if (!notification.isRead()) {
            notification.markAsRead();
            return "알림 읽음 처리 성공";
        } else {
            return "이미 읽은 알림입니다";
        }
    }

    @Transactional
    public void sendMessageToUser(Long memberId, FcmSendDto fcmSendDto) {
        outboxService.enqueue(memberId, fcmSendDto);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int sendTestMessageToUser(Long memberId, FcmSendDto fcmSendDto) {
        int sent = 0;
        long deadline = System.nanoTime() + deliveryProperties.adminTimeout().toNanos();
        List<MemberFcmToken> tokens = memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(memberId);
        for (MemberFcmToken tokenEntity : tokens) {
            if (deadline - System.nanoTime() < firebaseProperties.http().totalTimeout().toNanos()) {
                break;
            }
            if (transport.send(tokenEntity.getToken(), fcmSendDto,
                    () -> memberFcmTokenRepository.isDeliverable(tokenEntity.getId(), memberId)).success()) {
                sent++;
            }
        }
        return sent;
    }

    public List<FcmCategoryResponse> getNotificationCategories() {
        Member member = memberUtil.getCurrentMember();

        return Arrays.stream(FcmCategory.values())
                .map(category -> {
                    long count = switch (category) {
                        case ALL -> fcmNotificationRepository.countByMemberFcmToken_MemberIdAndIsReadFalse(member.getId());
                        default -> fcmNotificationRepository.countByMemberFcmToken_MemberIdAndFcmCategoryAndIsReadFalse(member.getId(), category);
                    };
                    return FcmCategoryResponse.of(category, count);
                })
                .toList();
    }

    // 토스트 모달 알림
    public ToastResDto getToastNotification() {
        Member member = memberUtil.getCurrentMember();

        FamilyMembership myMembership = familyMembershipRepository.findByGuardianId(member.getId())
                .orElse(null);
        if (myMembership == null) {
            return null;
        }

        List<SeniorProfile> seniors = seniorProfileRepository
                .findAllByFamilyIdWithMember(myMembership.getFamily().getId());
        if (seniors.isEmpty()) {
            return null;
        }

        int[] thresholds = {0, 2, 4, 6};

        for (int threshold : thresholds) {
            for (SeniorProfile senior : seniors) {
                long unviewedCount = calculateUnviewedCount(member.getId(), senior);

                if ((threshold == 0 && unviewedCount == 0) ||
                    (threshold > 0 && unviewedCount > 0 && unviewedCount <= threshold)) {
                    return createToastMessage(senior.getMember().getName(), unviewedCount);
                }
            }
        }

        return null;
    }

    private long calculateUnviewedCount(Long guardianId, SeniorProfile senior) {
        Long seniorMemberId = senior.getMember().getId();
        long totalCount = albumViewRepository.countTotalAlbumsByParent(guardianId);
        long viewedCount = albumViewRepository.countViewedAlbumsByGuardianAndParent(seniorMemberId, guardianId);

        return totalCount - viewedCount;
    }

    private ToastResDto createToastMessage(String seniorName, long unviewedCount) {
        if (unviewedCount == 0) {
            return ToastResDto.from(seniorName + "님께서 모든 소식을 다 보셨어요.");
        }
            return ToastResDto.from(seniorName + "님께서 보실 소식이 " + unviewedCount + "개밖에 남지 않았어요.");
    }

    // 응원 알림 보내기
    @Transactional
    public void sendNotificationToMember(SendNotificationRequest sendNotificationRequest) {
        Member sender = memberUtil.getCurrentMember();
        Member receiver = memberRepository.findById(sendNotificationRequest.receiverId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));

        // 알림 제목: "보내는사람님이 받는사람님에게 응원메시지를 보냈어요."
        String title = sender.getName() + "님이 " + receiver.getName() + "님에게 응원메시지를 보냈어요.";

        FcmSendDto fcmSendDto = FcmSendDto.builder()
                .title(title)
                .content(sendNotificationRequest.content())
                .fcmCategory(FcmCategory.ETC)
                .scheme("")
                .image(sender.getProfileImage())
                .relatedMemberId(sender.getId())
                .build();

        // 받는 사람에게 알림 전송
        sendMessageToUser(sendNotificationRequest.receiverId(), fcmSendDto);
    }
}
