package com.widyu.album.repository;

import java.time.LocalDateTime;

/** 보호자 한 명의 마지막 앨범 업로드(없으면 가입) 시각. 앨범 미업로드 알림 판정에 쓴다(LLD-0104). */
public record GuardianLastActivityRow(Long guardianId, String guardianName, String profileImage, LocalDateTime lastActivityAt) {
}
