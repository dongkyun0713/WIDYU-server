package com.widyu.goal.medicineschedule.client;

import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import feign.Request;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "medicineApiClient",
        url = "${medicine.api.url}",
        configuration = MedicineApiClientConfig.class
)
public interface MedicineApiClient {

    /**
     * 의약품 검색 API
     * Feign이 자동으로 모든 파라미터를 URL 인코딩
     */
    @GetMapping("/getDrbEasyDrugList")
    MedicineApiResponse searchMedicines(
            @RequestParam("serviceKey") String serviceKey,
            @RequestParam("itemName") String itemName,
            @RequestParam("numOfRows") Integer numOfRows,
            @RequestParam("pageNo") Integer pageNo,
            @RequestParam("type") String type
    );

    // 키워드 없이 전체 조회 (배치 동기화용). options는 쿼리 파라미터가 아니라 이 호출의 타임아웃이다.
    @GetMapping("/getDrbEasyDrugList")
    MedicineApiResponse fetchAllMedicines(
            @RequestParam("serviceKey") String serviceKey,
            @RequestParam("numOfRows") Integer numOfRows,
            @RequestParam("pageNo") Integer pageNo,
            @RequestParam("type") String type,
            Request.Options options
    );
}
