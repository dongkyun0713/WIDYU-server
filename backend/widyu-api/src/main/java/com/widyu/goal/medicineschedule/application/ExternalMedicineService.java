package com.widyu.goal.medicineschedule.application;

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
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExternalMedicineService {

    private final MedicineApiClient medicineApiClient;
    private final MedicineRepository medicineRepository;
    private final MedicineProperties medicineProperties;

    /**
     * 약품 검색: 자체 DB 우선 조회 후 없으면 외부 API fallback
     */
    @Transactional
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
                return MedicineSearchResponse.empty();
            }

            List<MedicineApiResponse.MedicineItem> apiItems = getApiItems(response);

            if (apiItems.isEmpty()) {
                log.warn("외부 API 검색 결과가 없음");
                return MedicineSearchResponse.empty();
            }

            List<Medicine> saved = upsertMedicines(apiItems);
            log.info("외부 API 검색 완료: resultCount={}", saved.size());
            return toSearchResponse(saved);

        } catch (DataIntegrityViolationException e) {
            log.warn("약품 중복 저장 감지, DB 재조회");
            List<Medicine> fallbackResults = medicineRepository.searchByNameFullText(keyword);
            return toSearchResponse(fallbackResults);
        } catch (Exception e) {
            log.error("약품 검색 실패: errorType={}", e.getClass().getSimpleName());
            return MedicineSearchResponse.empty();
        }
    }

    /**
     * 배치 동기화용 upsert: 이미 존재하는 itemSeq는 건너뛰고 신규만 저장
     */
    @Transactional
    public List<Medicine> upsertMedicines(List<MedicineApiResponse.MedicineItem> apiItems) {
        List<String> seqs = apiItems.stream()
                .filter(item -> item.itemSeq() != null)
                .map(MedicineApiResponse.MedicineItem::itemSeq)
                .collect(Collectors.toList());

        Set<String> existingSeqs = medicineRepository.findItemSeqsByItemSeqIn(seqs);

        Set<String> seenSeqs = new HashSet<>();
        List<Medicine> toSave = apiItems.stream()
                .filter(item -> item.itemSeq() != null
                        && !existingSeqs.contains(item.itemSeq())
                        && seenSeqs.add(item.itemSeq()))
                .map(item -> Medicine.create(
                        item.itemSeq(),
                        item.itemName(),
                        item.entpName(),
                        item.itemImage(),
                        item.useMethodQesitm(),
                        item.efcyQesitm()
                ))
                .collect(Collectors.toList());

        if (!toSave.isEmpty()) {
            return medicineRepository.saveAll(toSave);
        }

        return List.of();
    }

    private MedicineSearchResponse toSearchResponse(List<Medicine> medicines) {
        List<MedicineSearchResponse.MedicineItem> items = medicines.stream()
                .map(m -> new MedicineSearchResponse.MedicineItem(
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
