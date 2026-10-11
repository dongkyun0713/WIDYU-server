package com.widyu.followup.dto.response;

import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;
import com.widyu.followup.FollowupQ3;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public record FollowupCardResponse(Long id, String incidentRef, String questionSetVersion,
        long eventAtMs, long issuedAtMs, long expiresAtMs, String state, String introduction, String q1Question,
        String q2Question, String q3Question, List<FollowupQ1> q1Options, List<FollowupQ2> q2Options,
        List<FollowupQ3> q3Options) {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
            .withZone(ZoneId.of("Asia/Seoul"));

    public static FollowupCardResponse from(FollowupCard card) {
        String q1Question = "그때 몸의 불편함을 느끼셨나요?";
        if (card.getQuestionSetVersion().equals("FALL_V1")) {
            q1Question = "그때 실제로 넘어지셨나요?";
        }
        return new FollowupCardResponse(card.getId(), card.getIncidentRef(), card.getQuestionSetVersion(),
                card.getEventAtMs(), card.getIssuedAtMs(), card.getExpiresAtMs(), card.getState().name(),
                "아까 " + TIME_FORMAT.format(Instant.ofEpochMilli(card.getEventAtMs()))
                        + " 상황을 알려주세요. 기억나지 않거나 답하고 싶지 않으셔도 괜찮아요.",
                q1Question, "그때 다른 사람의 도움이 필요했나요?", "기억나는 상황이 있나요?",
                List.of(FollowupQ1.values()), List.of(FollowupQ2.values()), List.of(FollowupQ3.values()));
    }
}
