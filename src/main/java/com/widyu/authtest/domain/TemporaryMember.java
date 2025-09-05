package com.widyu.authtest.domain;

import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

@Getter
@EqualsAndHashCode(of = "id")
@RedisHash(value = "temporaryMember")
public class TemporaryMember {

    @Id
    private String id;

    private String name;
    private String phoneNumber;

    @TimeToLive
    private final long ttl;

    @Builder(access = AccessLevel.PRIVATE)
    private TemporaryMember(final String id, final String name, final String phoneNumber, final long ttl) {
        this.id = id;
        this.name = name;
        this.phoneNumber = phoneNumber;
        this.ttl = ttl;
    }

    public static TemporaryMember createTemporaryMember(final String name, final String phoneNumber) {
        return TemporaryMember.builder()
                .id(UUID.randomUUID().toString())
                .name(name)
                .phoneNumber(phoneNumber)
                .ttl(1800)
                .build();
    }
}
