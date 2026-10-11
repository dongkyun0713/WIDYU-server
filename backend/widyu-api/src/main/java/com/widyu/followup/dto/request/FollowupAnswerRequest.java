package com.widyu.followup.dto.request;

import com.widyu.followup.FollowupQ1;
import com.widyu.followup.FollowupQ2;
import com.widyu.followup.FollowupQ3;
import java.util.List;

public record FollowupAnswerRequest(String questionSetVersion, FollowupQ1 q1, FollowupQ2 q2,
        List<FollowupQ3> q3, Long deviceSubmittedAtMs) {}
