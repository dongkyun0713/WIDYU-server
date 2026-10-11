package com.widyu.followup.application;

import com.widyu.global.properties.SensorProperties;
import com.widyu.member.application.SeniorProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FollowupRewardService {
    private static final long REWARD_POINTS = 10L;
    private static final String REWARD_DESCRIPTION = "후속 질문 답변";

    private final SensorProperties sensorProperties;
    private final SeniorProfileService seniorProfileService;

    public void rewardIfEnabled(Long cardId, Long seniorId) {
        SensorProperties.Followup followup = sensorProperties.followup();
        if (followup == null || !followup.rewardEnabled()) {
            return;
        }
        seniorProfileService.addPointsToMember(seniorId, REWARD_POINTS,
                REWARD_DESCRIPTION, "FOLLOWUP:" + cardId);
    }
}
