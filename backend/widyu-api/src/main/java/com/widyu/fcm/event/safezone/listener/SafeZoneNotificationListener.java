package com.widyu.fcm.event.safezone.listener;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.event.safezone.dto.SafeZoneEnterEvent;
import com.widyu.global.entity.Status;
import com.widyu.member.FamilyMembership;
import com.widyu.member.Member;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.MemberRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 안심구역 전이를 위치 갱신 트랜잭션 안에서 일반 보호자 알림으로 예약한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafeZoneNotificationListener {

    private final MemberRepository members;
    private final SeniorProfileRepository seniors;
    private final FamilyMembershipRepository memberships;
    private final FcmOutboxService outbox;

    @EventListener
    public void handleSafeZoneExit(SafeZoneExitEvent event) {
        enqueue(event.seniorMemberId(), NotificationType.SAFE_ZONE_EXITED, "Z01");
    }

    @EventListener
    public void handleSafeZoneEnter(SafeZoneEnterEvent event) {
        enqueue(event.seniorMemberId(), NotificationType.SAFE_ZONE_ENTERED, "Z02");
    }

    private void enqueue(Long seniorId, NotificationType type, String copyCode) {
        Member senior = members.findById(seniorId).orElse(null);
        Long familyId = seniors.findFamilyIdByMemberId(seniorId).orElse(null);
        if (senior == null || familyId == null || senior.getStatus() != Status.ACTIVE) {
            log.warn("안심구역 알림 수신자 없음: seniorId={}", seniorId);
            return;
        }
        NotificationCopy copy = NotificationCopy.of(type, copyCode, Map.of("시니어 이름", senior.getName()));
        FcmSendDto message = FcmSendDto.builder()
                .title(copy.title()).content(copy.body()).notificationType(type)
                .eventId(UUID.randomUUID().toString()).seniorId(seniorId)
                .relatedMemberId(seniorId).image(senior.getProfileImage())
                .emergency(false).build();
        int recipients = 0;
        for (FamilyMembership membership : memberships.findAllByFamilyIdWithGuardian(familyId)) {
            if (membership.getGuardian().getStatus() != Status.ACTIVE) {
                continue;
            }
            outbox.enqueue(membership.getGuardian().getId(), message);
            recipients++;
        }
        if (recipients == 0) {
            log.warn("안심구역 알림 수신자 없음: seniorId={}", seniorId);
        }
    }
}
