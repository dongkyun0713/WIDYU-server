package com.widyu.mypage.event;

public record FamilyLeaderChangedEvent(Long newLeaderId) {
    public static FamilyLeaderChangedEvent of(Long newLeaderId) {
        return new FamilyLeaderChangedEvent(newLeaderId);
    }
}
