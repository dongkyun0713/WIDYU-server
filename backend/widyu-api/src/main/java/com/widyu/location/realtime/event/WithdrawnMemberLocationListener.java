package com.widyu.location.realtime.event;

import com.widyu.auth.event.MemberWithdrawnEvent;
import com.widyu.location.realtime.application.RealtimeLocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnMemberLocationListener {

    private final RealtimeLocationService realtimeLocationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void deleteLocationAfterCommit(MemberWithdrawnEvent event) {
        try {
            realtimeLocationService.deleteLocation(event.memberId());
        } catch (Exception e) {
            log.warn("탈퇴 회원 Redis 위치 삭제 실패: memberId={}, errorType={}",
                    event.memberId(), e.getClass().getSimpleName());
        }
    }
}
