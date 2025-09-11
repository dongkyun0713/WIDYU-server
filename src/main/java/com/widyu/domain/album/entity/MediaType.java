package com.widyu.domain.album.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MediaType {
    PHOTO("사진"),
    VIDEO("동영상");

    private final String description;
}
