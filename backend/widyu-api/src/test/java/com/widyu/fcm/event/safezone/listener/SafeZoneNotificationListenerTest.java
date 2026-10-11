package com.widyu.fcm.event.safezone.listener;

import static org.mockito.BDDMockito.then;

import com.widyu.fcm.event.safezone.dto.SafeZoneExitEvent;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.application.IncidentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SafeZoneNotificationListenerTest {

    @Mock private IncidentService incidentService;
    @InjectMocks private SafeZoneNotificationListener listener;

    @Test
    @DisplayName("안심구역 이탈 이벤트를 처리하면 판정 없는 안심구역 사건을 연다")
    void 안심구역_이탈_이벤트를_처리하면_판정_없는_사건을_연다() {
        // when
        listener.handleSafeZoneExit(new SafeZoneExitEvent(1L));

        // then
        then(incidentService).should().openForAlert(1L, IncidentKind.SAFE_ZONE_EXIT);
    }
}
