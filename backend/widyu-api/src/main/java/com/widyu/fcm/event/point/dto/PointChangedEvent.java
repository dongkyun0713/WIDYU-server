package com.widyu.fcm.event.point.dto;

import com.widyu.member.PointHistory;
import com.widyu.member.PointHistoryType;

public record PointChangedEvent(Long seniorId, PointHistoryType type, long points, String description, String eventId) {

    public static PointChangedEvent from(Long seniorId, PointHistory history) {
        if (history.getId() == null) {
            throw new IllegalStateException("포인트 내역 ID가 없어 알림을 만들 수 없습니다.");
        }
        String eventId = "POINT:E:" + history.getId();
        if (history.getType() == PointHistoryType.USE) {
            eventId = "POINT:U:" + history.getId();
        }
        return new PointChangedEvent(seniorId, history.getType(), history.getAmount(),
                history.getDescription(), eventId);
    }
}
