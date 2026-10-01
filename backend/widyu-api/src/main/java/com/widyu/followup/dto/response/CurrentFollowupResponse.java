package com.widyu.followup.dto.response;

import com.widyu.followup.FollowupCard;

public record CurrentFollowupResponse(FollowupCardResponse card, long serverTimeMs) {
    public static CurrentFollowupResponse of(FollowupCard card, long serverTimeMs) {
        FollowupCardResponse response = null;
        if (card != null) {
            response = FollowupCardResponse.from(card);
        }
        return new CurrentFollowupResponse(response, serverTimeMs);
    }
}
