package com.widyu.auth.event;

/**
 * 회원 탈퇴 트랜잭션이 끝날 때 발행한다. 리스너는 커밋 뒤(AFTER_COMMIT)에만 외부 정리를 한다. → LLD-0059
 */
public record MemberWithdrawnEvent(Long memberId) {
}
