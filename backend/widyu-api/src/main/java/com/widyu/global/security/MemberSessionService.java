package com.widyu.global.security;

import com.widyu.global.entity.Status;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.LocalAccount;
import com.widyu.auth.TemporaryMember;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberSessionService {
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    @Transactional
    public Member lock(Long memberId) {
        Member managed = entityManager.find(Member.class, memberId);
        if (managed != null && entityManager.getLockMode(managed) == LockModeType.PESSIMISTIC_WRITE) {
            return managed;
        }
        Member member = entityManager.find(Member.class, memberId, LockModeType.PESSIMISTIC_WRITE);
        if (member == null) {
            throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
        // 잠금 아래 신규 가입의 미flush 필드와 cascade 관계를 먼저 보존한다.
        entityManager.flush();
        // 로그인 등에서 먼저 읽은 영속 엔티티도 잠금 획득 후 현재 값으로 갱신한다.
        entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
        return member;
    }

    @Transactional
    public Member revoke(Long memberId) {
        Member member = lock(memberId);
        member.revokeSessions();
        events.publishEvent(new SessionsRevoked(memberId));
        return member;
    }

    @Transactional
    public LocalAccount lockLocalAccount(Long memberId) {
        lock(memberId);
        LocalAccount account = entityManager.createQuery(
                        "select a from LocalAccount a where a.member.id = :memberId", LocalAccount.class)
                .setParameter("memberId", memberId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_EMAIL));
        // Member refresh의 cascade와 일반 SELECT는 MySQL RR의 옛 계정 값을 남길 수 있다.
        // 계정 자체를 current-read refresh한 뒤에만 비밀번호를 검증하거나 변경한다.
        entityManager.refresh(account, LockModeType.PESSIMISTIC_WRITE);
        return account;
    }

    @Transactional
    public Member validateTemporaryMember(TemporaryMember temporary, Long memberId) {
        Member member = lock(memberId);
        member.requireActive();
        if (!memberId.equals(temporary.getMemberId()) || temporary.getAuthVersion() == null
                || temporary.getAuthVersion() != member.getAuthVersion()) {
            throw new BusinessException(ErrorCode.INVALID_TEMPORARY_TOKEN);
        }
        return member;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isCurrent(Long memberId, Long version) {
        if (memberId == null || version == null || version < 0) {
            return false;
        }
        return entityManager.createQuery(
                        "select count(m) from Member m where m.id = :id and m.authVersion = :version and m.status = :status", Long.class)
                .setParameter("id", memberId)
                .setParameter("version", version)
                .setParameter("status", Status.ACTIVE)
                .getSingleResult() == 1;
    }

    public record SessionsRevoked(Long memberId) {}
}
