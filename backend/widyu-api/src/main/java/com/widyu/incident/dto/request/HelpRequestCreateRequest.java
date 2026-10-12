package com.widyu.incident.dto.request;

import com.widyu.incident.ResponseVia;

/** 요청 본문은 선택이다. via는 원 클릭 기기만 나타낸다. */
public record HelpRequestCreateRequest(Long deviceRequestedAtMs, ResponseVia via) {
}
