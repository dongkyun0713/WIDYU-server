package com.widyu.fcm.application.degraded;

import com.widyu.fcm.FcmCategory;
import com.widyu.fcm.FcmNotification;
import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.NotificationPolicy;
import com.widyu.fcm.application.FcmOutboxService;
import com.widyu.fcm.repository.FcmNotificationRepository;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 저하 경로 발송 기록 1건을 알림 이력으로 옮긴다(LLD-0087 5.1). 항목마다 새 트랜잭션이라 한 건의 DB 실패가
 * 앞서 옮긴 건을 되돌리지 않는다. 보정기와 다른 빈이어야 프록시가 REQUIRES_NEW를 건다.
 */
@Service
@RequiredArgsConstructor
public class DegradedAlertReconcileTransactions {

    static final String DECISION_PREFIX = "degraded:";

    private final FcmNotificationRepository notifications;
    private final MemberFcmTokenRepository tokens;
    private final MemberRepository members;

    enum Outcome { SAVED, DUPLICATE, TOKEN_MISSING, RECIPIENT_MISSING }

    /** 보정 목록 JSON 1건(LLD-0086 5.4). 토큰 문자열은 싣지 않는다. */
    record ReconcileRecord(Long guardianId, Long tokenId, Long seniorId, String title, String body, String image,
            String category, Long sentAtMs, String streamId) {

        boolean complete() {
            return guardianId != null && tokenId != null && seniorId != null && streamId != null
                    && category != null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome persist(ReconcileRecord record) {
        String decisionId = DECISION_PREFIX + record.streamId();
        if (notifications.existsByDecisionId(decisionId)) {
            return Outcome.DUPLICATE;
        }
        MemberFcmToken token = tokens.findById(record.tokenId()).orElse(null);
        if (token == null) {
            return Outcome.TOKEN_MISSING;
        }
        // 토큰 주인이 바뀌었어도 수신자는 보낸 당시의 보호자다(ADR-0028).
        Member guardian = members.findById(record.guardianId()).orElse(null);
        if (guardian == null) {
            return Outcome.RECIPIENT_MISSING;
        }
        NotificationPolicy type = NotificationPolicy.safety("S01/S03/S04/S06", true);
        notifications.save(FcmNotification.builder()
                .recipientMember(guardian).memberFcmToken(token)
                .eventId(record.streamId()).policy(type)
                .fcmCategory(FcmCategory.valueOf(record.category()))
                .title(record.title()).body(record.body()).image(record.image())
                .deepLink(type.deepLinkTemplate().replace("{seniorId}", record.seniorId().toString()))
                .seniorId(record.seniorId())
                .decisionId(decisionId)
                .expiresAt(LocalDateTime.now().plus(type.retentionClass().duration()))
                .retentionPolicyVersion(FcmOutboxService.RETENTION_POLICY_VERSION)
                .pushEligible(true)
                .isRead(false).build());
        return Outcome.SAVED;
    }
}
