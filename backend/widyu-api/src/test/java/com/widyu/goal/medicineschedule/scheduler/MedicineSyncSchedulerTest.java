package com.widyu.goal.medicineschedule.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.application.MedicineUpsertService;
import com.widyu.goal.medicineschedule.client.MedicineApiClient;
import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import com.widyu.medicine.Medicine;
import feign.Request;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicineSyncScheduler 단위 테스트")
class MedicineSyncSchedulerTest {

    @Mock private MedicineApiClient medicineApiClient;
    @Mock private MedicineUpsertService medicineUpsertService;
    @Mock private MedicineProperties medicineProperties;

    @Captor private ArgumentCaptor<List<MedicineApiResponse.MedicineItem>> itemsCaptor;
    @Captor private ArgumentCaptor<Request.Options> optionsCaptor;

    @InjectMocks private MedicineSyncScheduler medicineSyncScheduler;

    @Test
    @DisplayName("페이지 조회가 일시 실패한 후 재시도하면 다음 페이지까지 동기화한다")
    void 페이지_조회가_일시_실패한_후_재시도하면_다음_페이지까지_동기화한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.fetchAllMedicines(anyString(), anyInt(), anyInt(), anyString(), any(Request.Options.class)))
                .willThrow(new IllegalStateException("temporary failure"))
                .willReturn(responseWithItems(100))
                .willReturn(emptyResponse());
        given(medicineUpsertService.upsertMedicines(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(savedMedicines(30));

        // when
        int totalSynced = medicineSyncScheduler.syncMedicinePages();

        // then
        verify(medicineApiClient, times(2)).fetchAllMedicines(
                eq("service-key"), eq(100), eq(1), eq("json"), any(Request.Options.class));
        verify(medicineApiClient).fetchAllMedicines(
                eq("service-key"), eq(100), eq(2), eq("json"), any(Request.Options.class));
        verify(medicineUpsertService).upsertMedicines(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue()).hasSize(100);
        assertThat(totalSynced).isEqualTo(30);
    }

    @Test
    @DisplayName("페이지 재시도가 소진되면 이후 동기화를 중단한다")
    void 페이지_재시도가_소진되면_이후_동기화를_중단한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.fetchAllMedicines(anyString(), anyInt(), anyInt(), anyString(), any(Request.Options.class)))
                .willThrow(new IllegalStateException("persistent failure"));

        // when
        int totalSynced = medicineSyncScheduler.syncMedicinePages();

        // then
        verify(medicineApiClient, times(3)).fetchAllMedicines(
                eq("service-key"), eq(100), eq(1), eq("json"), any(Request.Options.class));
        verify(medicineUpsertService, never()).upsertMedicines(org.mockito.ArgumentMatchers.anyList());
        assertThat(totalSynced).isZero();
    }

    @Test
    @DisplayName("배치 페이지 조회는 검색과 별도로 설정한 배치 읽기 타임아웃을 사용한다")
    void 배치_페이지_조회는_배치_읽기_타임아웃을_사용한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.fetchAllMedicines(anyString(), anyInt(), anyInt(), anyString(), any(Request.Options.class)))
                .willReturn(emptyResponse());

        // when
        medicineSyncScheduler.syncMedicinePages();

        // then
        verify(medicineApiClient).fetchAllMedicines(
                eq("service-key"), eq(100), eq(1), eq("json"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue().connectTimeoutMillis()).isEqualTo(2_000);
        assertThat(optionsCaptor.getValue().readTimeoutMillis()).isEqualTo(30_000);
    }

    private MedicineProperties.Api apiProperties() {
        return new MedicineProperties.Api(
                "url", "service-key", Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(30));
    }

    private MedicineApiResponse emptyResponse() {
        return new MedicineApiResponse(
                null,
                new MedicineApiResponse.Body(1, 0, 100, List.of(), null)
        );
    }

    private MedicineApiResponse responseWithItems(int count) {
        List<MedicineApiResponse.MedicineItem> items = IntStream.range(0, count)
                .mapToObj(index -> new MedicineApiResponse.MedicineItem(
                        "제조사",
                        "약품" + index,
                        "seq-" + index,
                        null,
                        null,
                        null
                ))
                .toList();
        return new MedicineApiResponse(null, new MedicineApiResponse.Body(1, count, 100, items, null));
    }

    private List<Medicine> savedMedicines(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> org.mockito.Mockito.mock(Medicine.class))
                .toList();
    }
}
