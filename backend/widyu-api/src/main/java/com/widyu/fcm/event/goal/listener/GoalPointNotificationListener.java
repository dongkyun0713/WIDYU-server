package com.widyu.fcm.event.goal.listener;

import com.widyu.fcm.NotificationType;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.GuardianDeepLinks;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.event.goal.dto.GoalAchievedEvent;
import com.widyu.fcm.event.point.dto.PointChangedEvent;
import com.widyu.member.FamilyMembership;
import com.widyu.member.PointHistoryType;
import com.widyu.member.SeniorProfile;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoalPointNotificationListener {

    private final FcmOutboxService outboxService;
    private final SeniorProfileRepository seniorProfileRepository;
    private final FamilyMembershipRepository familyMembershipRepository;

    @EventListener
    public void onPointChanged(PointChangedEvent event) {
        NotificationType type = NotificationType.POINT_EARNED;
        Map<String, String> values = Map.of("포인트", Long.toString(event.points()),
                "적립 사유", event.description());
        if (event.type() == PointHistoryType.USE) {
            type = NotificationType.POINT_USED;
            values = Map.of("포인트", Long.toString(event.points()), "사용 사유", event.description());
        }
        NotificationCopy copy = NotificationCopy.of(type, type.copyCode(), values);
        outboxService.enqueue(event.seniorId(), FcmSendDto.builder()
                .title(copy.title()).content(copy.body()).notificationType(type)
                .eventId(event.eventId()).build());
    }

    @EventListener
    public void onGoalAchieved(GoalAchievedEvent event) {
        SeniorProfile profile = seniorProfileRepository.findByMemberId(event.seniorId())
                .orElseThrow(() -> new IllegalStateException("목표 달성 시니어 프로필이 없습니다."));
        String seniorName = profile.getMember().getName();
        Map<String, String> seniorValues = Map.of("목표명", event.goalName(),
                "포인트", Long.toString(event.points()));
        NotificationCopy seniorCopy = NotificationCopy.of(NotificationType.GOAL_ACHIEVED, "G01-S", seniorValues);
        outboxService.enqueue(event.seniorId(), FcmSendDto.builder()
                .title(seniorCopy.title()).content(seniorCopy.body())
                .notificationType(NotificationType.GOAL_ACHIEVED).eventId(event.eventId())
                .entityId(event.entityId()).seniorId(event.seniorId()).build());

        if (profile.getFamily() == null) {
            return;
        }
        for (FamilyMembership membership : familyMembershipRepository
                .findAllByFamilyIdWithGuardian(profile.getFamily().getId())) {
            Map<String, String> guardianValues = Map.of("시니어 이름", seniorName,
                    "목표명", event.goalName(), "포인트", Long.toString(event.points()));
            NotificationCopy guardianCopy = NotificationCopy.of(
                    NotificationType.GOAL_ACHIEVED, "G01-C", guardianValues);
            String guardianDeepLink = GuardianDeepLinks.medicineGoal(event.seniorId());
            outboxService.enqueue(membership.getGuardian().getId(), FcmSendDto.builder()
                    .title(guardianCopy.title()).content(guardianCopy.body())
                    .notificationType(NotificationType.GOAL_ACHIEVED).eventId(event.eventId())
                    .entityId(event.entityId()).seniorId(event.seniorId())
                    .relatedMemberId(event.seniorId()).deepLink(guardianDeepLink).build());
        }
    }
}
