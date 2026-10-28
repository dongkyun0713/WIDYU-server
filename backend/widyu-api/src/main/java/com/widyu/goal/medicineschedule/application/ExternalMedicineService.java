package com.widyu.goal.medicineschedule.application;

import com.widyu.global.error.DataIntegrityViolationKind;
import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.client.MedicineApiClient;
import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineSearchResponse;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.medicine.Medicine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 약품 검색. 트랜잭션을 걸지 않는다: 외부 API를 기다리는 동안 DB 트랜잭션이 열려 있지 않아야 하고,
 * 저장 충돌 복구 조회는 롤백된 저장 트랜잭션 밖에서 해야 한다(LLD-0100).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalMedicineService {

    private static final int MAX_SAVE_ATTEMPTS = 2;

    private final MedicineApiClient medicineApiClient;
    private final MedicineRepository medicineRepository;
    private final MedicineUpsertService medicineUpsertService;
    private final MedicineProperties medicineProperties;

    /**
     * 약품 검색: 자체 DB 우선 조회 후 없으면 외부 API fallback
     */
    public MedicineSearchResponse searchAndSaveMedicines(String keyword) {
        log.info("약품 검색 시작");

        // 1. 자체 DB 검색 (2글자 미만은 prefix LIKE, 이상은 FULLTEXT)
        List<Medicine> dbResults;
        if (keyword.length() < 2) {
            dbResults = medicineRepository.searchByNamePrefix(keyword + "%");
        } else {
            dbResults = medicineRepository.searchByNameFullText(keyword);
        }
        if (!dbResults.isEmpty()) {
            log.info("자체 DB 검색 성공: resultCount={}", dbResults.size());
            return toSearchResponse(dbResults);
        }

        // 2. DB에 없으면 외부 API fallback
        log.info("자체 DB 결과 없음, 외부 API 호출");
        List<MedicineApiResponse.MedicineItem> apiItems = fetchApiItems(keyword);
        if (apiItems.isEmpty()) {
            return MedicineSearchResponse.empty();
        }

        // 3. 신규 약품만 저장하고, 응답은 API가 준 itemSeq로 DB에서 다시 읽는다.
        //    다른 요청이 같은 약품을 먼저 저장했으면 저장은 건너뛰거나 충돌하지만 응답에는 그 약품이 담긴다.
        saveNewMedicines(apiItems);
        List<Medicine> medicines = medicineRepository.findByItemSeqIn(itemSeqs(apiItems));
        log.info("외부 API 검색 완료: resultCount={}", medicines.size());
        return toSearchResponse(medicines);
    }

    /**
     * 존재 확인 뒤 다른 요청이 같은 itemSeq를 먼저 커밋하면 유일 제약 충돌로 이 저장 트랜잭션 전체가 롤백된다.
     * 한 번 더 저장하면 존재 확인이 그 약품을 건너뛰어 함께 받은 나머지 신규 약품을 저장한다.
     * 두 번째도 충돌하면 저장을 포기하고 이미 저장된 약품만 응답한다. 중복 외의 무결성 위반과 DB 예외는 전파한다.
     */
    private void saveNewMedicines(List<MedicineApiResponse.MedicineItem> apiItems) {
        for (int attempt = 1; attempt <= MAX_SAVE_ATTEMPTS; attempt++) {
            try {
                medicineUpsertService.upsertMedicines(apiItems);
                return;
            } catch (DataIntegrityViolationException e) {
                if (DataIntegrityViolationKind.classify(e).type() != DataIntegrityViolationKind.Type.DUPLICATE) {
                    throw e;
                }
                log.warn("약품 중복 저장 감지: attempt={}/{}", attempt, MAX_SAVE_ATTEMPTS);
            }
        }
    }

    /**
     * 외부 API 호출·파싱 실패(타임아웃·5xx 포함)는 빈 목록으로 격리한다.
     * Feign 예외 메시지에는 serviceKey가 담긴 요청 URL이 들어 있어 예외 종류만 남긴다.
     */
    private List<MedicineApiResponse.MedicineItem> fetchApiItems(String keyword) {
        try {
            MedicineApiResponse response = medicineApiClient.searchMedicines(
                    medicineProperties.api().serviceKey(),
                    keyword,
                    10,
                    1,
                    "json"
            );

            if (response == null || response.body() == null) {
                log.warn("외부 API 응답이 비어있음");
                return List.of();
            }

            List<MedicineApiResponse.MedicineItem> apiItems = getApiItems(response);
            if (apiItems.isEmpty()) {
                log.warn("외부 API 검색 결과가 없음");
            }
            return apiItems;
        } catch (RuntimeException e) {
            log.error("약품 검색 외부 API 실패: errorType={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private List<String> itemSeqs(List<MedicineApiResponse.MedicineItem> apiItems) {
        return apiItems.stream()
                .map(MedicineApiResponse.MedicineItem::itemSeq)
                .filter(Objects::nonNull)
                .toList();
    }

    private MedicineSearchResponse toSearchResponse(List<Medicine> medicines) {
        List<MedicineSearchResponse.MedicineItem> items = medicines.stream()
                .map(m -> MedicineSearchResponse.MedicineItem.of(
                        m.getId(),
                        m.getItemName(),
                        m.getItemImage(),
                        m.getUseMethodQesitm(),
                        m.getEfcyQesitm()
                ))
                .collect(Collectors.toList());
        return MedicineSearchResponse.of(items);
    }

    private List<MedicineApiResponse.MedicineItem> getApiItems(MedicineApiResponse response) {
        if (response.body().items() != null) {
            return response.body().items();
        }
        if (response.body().item() != null) {
            return response.body().item();
        }
        return List.of();
    }
}
