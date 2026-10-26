package com.widyu.fcm.event.goal.walk.listener;

import com.widyu.fcm.application.FcmService;
import com.widyu.fcm.dto.FcmSendDto;
import com.widyu.fcm.dto.NotificationCopy;
import com.widyu.fcm.NotificationType;
import com.widyu.goal.walk.repository.WalkRepository;
import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.SeniorProfileRepository;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalkNotificationListener {

    private final FcmService fcmService;
    private final WalkRepository walkRepository;
    private final SeniorProfileRepository seniorProfileRepository;

    /**
     * 매일 저녁 7시에 실행되어 오늘 만보계 목표 미달성자에게 알림 발송
     */
    @Scheduled(cron = "0 0 19 * * *")
    @Transactional
    public void sendWalkGoalReminderToUnachieved() {
        LocalDate today = LocalDate.now();

        log.info("만보계 목표 미달성 알림 스케줄러 실행 - 대상 날짜: {}", today);

        // 오늘 걸음 기록이 있는 미달성자와, 목표는 있는데 오늘 기록이 없는(0걸음) 시니어
        List<Walk> unachievedWalks = walkRepository.findUnachievedWalksByDate(today);
        List<Member> notSyncedMembers =
                seniorProfileRepository.findMembersWithWalkGoalButNoWalkOn(today, Status.ACTIVE);

        if (unachievedWalks.isEmpty() && notSyncedMembers.isEmpty()) {
            log.info("오늘 만보계 목표 미달성자가 없습니다.");
            return;
        }

        log.info("{}명의 시니어에게 만보계 목표 미달성 알림을 발송합니다.",
                unachievedWalks.size() + notSyncedMembers.size());

        for (Walk walk : unachievedWalks) {
            if (walk.isGoalAchieved()) {
                continue;
            }
            sendQuietly(walk.getMember(), walk.getActualSteps());
        }
        for (Member member : notSyncedMembers) {
            sendQuietly(member, 0);
        }
    }

    private void sendQuietly(Member member, Integer actualSteps) {
        try {
            sendUnachievedNotification(member, actualSteps);
        } catch (Exception e) {
            log.error("만보계 미달성 알림 발송 실패 - memberId: {}", member.getId(), e);
        }
    }

    /**
     * 개별 미달성자에게 알림 발송. 0걸음은 걷지 않았는지 동기화가 안 됐는지 서버가 구분할 수 없지만
     * 목표가 있으면 보낸다(#801, 알림 문구 카탈로그 v0.4의 「0이면 만들지 않음」을 대체).
     */
    private void sendUnachievedNotification(Member member, Integer actualSteps) {
        if (member.getType() != MemberType.SENIOR || actualSteps == null) {
            return;
        }
        NotificationCopy copy = NotificationCopy.of(NotificationType.WALK_GOAL_UNMET, "W01",
                Map.of("실제 걸음 수", actualSteps.toString()));
        FcmSendDto dto = FcmSendDto.of(NotificationType.WALK_GOAL_UNMET, copy,
                null, null, null, null, member.getProfileImage());
        fcmService.sendMessageToUser(member.getId(), dto);
    }
}
