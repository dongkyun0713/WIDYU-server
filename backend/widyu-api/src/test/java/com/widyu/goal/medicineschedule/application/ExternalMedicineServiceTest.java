package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.client.MedicineApiClient;
import com.widyu.goal.medicineschedule.dto.response.MedicineSearchResponse;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.medicine.Medicine;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExternalMedicineServiceTest {

    @Mock private MedicineApiClient medicineApiClient;
    @Mock private MedicineRepository medicineRepository;
    @Mock private MedicineProperties medicineProperties;
    @InjectMocks private ExternalMedicineService externalMedicineService;

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "타", " 타 "})
    @DisplayName("공백을 뺀 검색어가 2글자 미만이면 DB와 외부 API를 호출하지 않고 빈 목록을 반환한다")
    void 검색어가_2글자_미만이면_조회하지_않고_빈_목록을_반환한다(String keyword) {
        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(keyword);

        // then
        assertThat(response.medicines()).isEmpty();
        then(medicineRepository).shouldHaveNoInteractions();
        then(medicineApiClient).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("2글자 이상 검색어는 FULLTEXT로 조회하고 결과가 있으면 외부 API를 호출하지 않는다")
    void 검색어가_2글자_이상이면_FULLTEXT로_조회한다() {
        // given
        given(medicineRepository.searchByNameFullText("타이")).willReturn(List.of(mock(Medicine.class)));

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines("타이");

        // then
        assertThat(response.medicines()).hasSize(1);
        then(medicineApiClient).should(never())
                .searchMedicines(anyString(), anyString(), anyInt(), anyInt(), anyString());
    }
}
