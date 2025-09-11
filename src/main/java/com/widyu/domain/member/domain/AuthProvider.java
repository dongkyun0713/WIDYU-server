package com.widyu.domain.member.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum AuthProvider {
    LOCAL("local"),
    KAKAO("kakao"),
    NAVER("naver"),
    APPLE("apple");

    private final String value;
}
