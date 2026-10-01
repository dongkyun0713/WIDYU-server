package com.widyu.followup.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.properties.SensorProperties;
import com.widyu.member.application.SeniorProfileService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FollowupRewardServiceTest {
    @Mock private SensorProperties properties;
    @Mock private SeniorProfileService seniorProfileService;
    @InjectMocks private FollowupRewardService rewardService;

    @Test
    @DisplayName("후속 보상을 켜고 첫 제출을 처리하면 고정 10P와 카드 키로 적립한다")
    void 후속_보상을_켜고_첫_제출을_처리하면_고정_포인트와_카드_키로_적립한다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, true));

        // when
        rewardService.rewardIfEnabled(71L, 17L);

        // then
        then(seniorProfileService).should().addPointsToMember(17L, 10L,
                "후속 질문 답변", "FOLLOWUP:71");
    }

    @Test
    @DisplayName("후속 보상을 끄면 포인트 적립을 호출하지 않는다")
    void 후속_보상을_끄면_포인트_적립을_호출하지_않는다() {
        // given
        given(properties.followup()).willReturn(new SensorProperties.Followup(true, false));

        // when
        rewardService.rewardIfEnabled(71L, 17L);

        // then
        then(seniorProfileService).should(never())
                .addPointsToMember(17L, 10L, "후속 질문 답변", "FOLLOWUP:71");
    }
}
