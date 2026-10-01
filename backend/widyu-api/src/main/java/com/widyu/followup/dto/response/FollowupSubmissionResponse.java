package com.widyu.followup.dto.response;

import com.widyu.followup.FollowupAnswer;
import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;

public record FollowupSubmissionResponse(Long cardId, String state, String questionSetVersion,
        FollowupQ1 q1, FollowupQ2 q2, String q3, long deviceSubmittedAtMs, long serverReceivedAtMs) {
    public static FollowupSubmissionResponse of(FollowupCard card, FollowupAnswer answer) {
        return new FollowupSubmissionResponse(card.getId(), card.getState().name(),
                answer.getQuestionSetVersion(), answer.getQ1(), answer.getQ2(), answer.getQ3(),
                answer.getDeviceSubmittedAtMs(), answer.getServerReceivedAtMs());
    }
}
