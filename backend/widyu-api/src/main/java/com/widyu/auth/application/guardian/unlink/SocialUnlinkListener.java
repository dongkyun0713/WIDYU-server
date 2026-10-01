package com.widyu.auth.application.guardian.unlink;

import com.widyu.auth.event.MemberWithdrawnEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SocialUnlinkListener {

    private final SocialUnlinkService socialUnlinkService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void unlinkAfterCommit(MemberWithdrawnEvent event) {
        socialUnlinkService.findPendingIds(event.memberId()).forEach(this::process);
    }

    private void process(Long taskId) {
        try {
            socialUnlinkService.process(taskId);
        } catch (Exception e) {
            log.error("소셜 연동 해제 작업 처리 실패: taskId={}, errorType={}", taskId, e.getClass().getSimpleName());
        }
    }
}
