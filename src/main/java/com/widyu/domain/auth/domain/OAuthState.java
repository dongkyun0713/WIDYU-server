package com.widyu.domain.auth.domain;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@Getter
@EqualsAndHashCode(of = "state")
@RedisHash(value = "oauthState")
public class OAuthState {
    @Id
    private final String state;

    @TimeToLive
    private final long ttl;

    @Builder
    public OAuthState(final String state, final long ttl) {
        this.state = state;
        this.ttl = ttl;
    }
}
