package com.widyu.followup.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.followup.repository.FollowupCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FollowupExpirySchedulerTest {
    @Mock private FollowupCardService cardService;
    @Mock private FollowupCardRepository cards;

    @Test
    @DisplayName("후속 기능을 끄면 만료 스케줄러가 저장소를 조회하지 않는다")
    void 후속_기능을_끄면_만료_스케줄러가_저장소를_조회하지_않는다() {
        // given
        given(cardService.enabled()).willReturn(false);

        // when
        new FollowupExpiryScheduler(cardService, cards).expire();

        // then
        verifyNoInteractions(cards);
    }
}
