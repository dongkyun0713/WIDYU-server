package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.widyu.run.application.CollectionRunService;
import com.widyu.sensor.dto.response.SensorConfigResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("SensorConfigService 단위 테스트")
class SensorConfigServiceTest {

    private static final Long MEMBER_ID = 1023L;

    @Mock private CollectionRunService collectionRunService;

    @Test
    @DisplayName("열린 회차가 있는 회원이 조회하면 수집 모드가 research다")
    void 열린_회차가_있는_회원이_조회하면_수집_모드가_research다() {
        // given
        given(collectionRunService.hasOpenRun(MEMBER_ID)).willReturn(true);

        // when
        SensorConfigResponse response = service().currentConfig(MEMBER_ID);

        // then
        assertThat(response.collectionMode()).isEqualTo("research");
    }

    @Test
    @DisplayName("열린 회차가 없는 회원이 조회하면 수집 모드가 product다")
    void 열린_회차가_없는_회원이_조회하면_수집_모드가_product다() {
        // given
        given(collectionRunService.hasOpenRun(MEMBER_ID)).willReturn(false);

        // when
        SensorConfigResponse response = service().currentConfig(MEMBER_ID);

        // then
        assertThat(response.collectionMode()).isEqualTo("product");
    }

    @Test
    @DisplayName("수집 모드를 뺀 나머지 값은 서버 설정값을 그대로 내려준다")
    void 수집_모드를_뺀_나머지_값은_서버_설정값을_그대로_내려준다() {
        // given
        given(collectionRunService.hasOpenRun(MEMBER_ID)).willReturn(false);

        // when
        SensorConfigResponse response = service().currentConfig(MEMBER_ID);

        // then
        assertThat(response.gyroMode()).isEqualTo("continuous");
        assertThat(response.accFsHz()).isEqualTo(50);
        assertThat(response.batchSec()).isEqualTo(1);
        assertThat(response.impactThresholdG()).isEqualTo(1.8);
        assertThat(response.backfillBeforeSec()).isEqualTo(2);
        assertThat(response.backfillAfterSec()).isEqualTo(10);
        assertThat(response.hrUploadSec()).isEqualTo(1);
        assertThat(response.locationMoveSec()).isEqualTo(5);
        assertThat(response.locationKeepaliveSec()).isEqualTo(60);
        assertThat(response.heartbeatSec()).isEqualTo(60);
        // 워치가 다시 읽을 권고 주기다.
        assertThat(response.refreshSec()).isEqualTo(60);
    }

    private SensorConfigService service() {
        return new SensorConfigService(SensorConfigFixture.properties(), collectionRunService);
    }
}
