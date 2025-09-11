package com.widyu.domain.auth.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum TokenType {
    ACCESS("access"),
    REFRESH("refresh"),
    TEMPORARY("temporary")
    ;
    private final String value;
}
