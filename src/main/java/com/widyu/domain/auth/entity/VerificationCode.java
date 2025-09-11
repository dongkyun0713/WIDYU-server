package com.widyu.domain.auth.entity;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@Getter
@NoArgsConstructor
@EqualsAndHashCode(of = "phoneNumber")
@RedisHash(value = "verificationCode")
public class VerificationCode {

    @Id
    private String phoneNumber;

    private String name;

    private String code;

    @TimeToLive
    private long ttl;

    @Builder
    public VerificationCode(final String phoneNumber, final String name, final String code, final long ttl) {
        this.phoneNumber = phoneNumber;
        this.name = name;
        this.code = code;
        this.ttl = ttl;
    }
}