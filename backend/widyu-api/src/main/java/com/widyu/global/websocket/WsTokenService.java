package com.widyu.global.websocket;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.MemberSessionService;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WsTokenService {

    private static final String KEY_PREFIX = "ws-token:";
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final MemberUtil memberUtil;
    private final MemberSessionService memberSessionService;

    @Transactional
    public String issueToken() {
        Member member = memberSessionService.lock(memberUtil.getCurrentMember().getId());
        member.requireActive();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof PrincipalDetails principal)
                || !member.getId().equals(principal.getMemberId())
                || principal.getAuthVersion() == null
                || principal.getAuthVersion() != member.getAuthVersion()) {
            throw new BusinessException(ErrorCode.INVALID_ACCESS_TOKEN);
        }
        String tokenId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, member.getId() + ":" + member.getAuthVersion(), TTL);
        return tokenId;
    }

    public Long validateAndConsume(String tokenId) {
        WsSessionIdentity identity = consumeIdentity(tokenId);
        if (identity == null) {
            return null;
        }
        return identity.memberId();
    }

    public WsSessionIdentity consumeIdentity(String tokenId) {
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + tokenId);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            Long memberId = Long.valueOf(parts[0]);
            Long version = Long.valueOf(parts[1]);
            if (memberSessionService.isCurrent(memberId, version)) {
                return new WsSessionIdentity(memberId, version);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    public record WsSessionIdentity(Long memberId, Long authVersion) {}
}
