package com.widyu.incident.dto.request;

import com.widyu.incident.ResponseVia;
import jakarta.validation.constraints.NotNull;

/** 시니어 기기가 확인 화면을 띄웠다는 알림(LLD-0085 3.1). */
public record PromptDisplayedRequest(@NotNull ResponseVia via) {
}
