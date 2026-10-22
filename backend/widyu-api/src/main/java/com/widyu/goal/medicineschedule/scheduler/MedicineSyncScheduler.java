package com.widyu.goal.medicineschedule.scheduler;

import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.application.MedicineUpsertService;
import com.widyu.goal.medicineschedule.client.MedicineApiClient;
import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import feign.Request;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 공공 의약품 API의 신규 데이터를 자체 DB에 주기적으로 보충
 * - 매월 1일 새벽 3시 실행 (트래픽 없는 시간대)
 * - 100건 단위 청크 처리로 DB 부하 분산
 * - 신규 약품만 INSERT (기존 데이터 스킵)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MedicineSyncScheduler {

    private static final int CHUNK_SIZE = 100;
    private static final long API_CALL_DELAY_MS = 300; // 공공 API 부하 방지
    private static final int MAX_PAGE_ATTEMPTS = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1_000;

    private final MedicineApiClient medicineApiClient;
    private final MedicineUpsertService medicineUpsertService;
    private final MedicineProperties medicineProperties;

    @Scheduled(cron = "0 0 3 1 * *")
    public void syncMedicineData() {
        log.info("의약품 DB 동기화 시작");
        int totalSynced = syncMedicinePages();
        log.info("의약품 DB 동기화 완료 - 총 {}건 처리", totalSynced);
    }

    int syncMedicinePages() {
        int page = 1;
        int totalSynced = 0;

        while (true) {
            try {
                PageSyncResult pageSyncResult = syncPageWithRetry(page);
                if (pageSyncResult == null) {
                    log.error("의약품 동기화 중단 - 재시도 소진 page: {}", page);
                    break;
                }

                if (pageSyncResult.fetchedCount() == 0) {
                    log.info("의약품 동기화 완료 - 마지막 페이지: {}", page);
                    break;
                }

                totalSynced += pageSyncResult.insertedCount();
                log.debug("의약품 동기화 진행 중 - page: {}, 누적: {}건", page, totalSynced);

                if (pageSyncResult.fetchedCount() < CHUNK_SIZE) {
                    break;
                }

                page++;
                Thread.sleep(API_CALL_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("의약품 동기화 인터럽트 - page: {}", page);
                break;
            }
        }

        return totalSynced;
    }

    private PageSyncResult syncPageWithRetry(int page) throws InterruptedException {
        long retryDelayMs = INITIAL_RETRY_DELAY_MS;
        MedicineProperties.Api api = medicineProperties.api();
        // 100건 페이지는 검색보다 응답이 크고 DB 트랜잭션 밖에서 호출하므로 읽기 타임아웃을 따로 둔다(LLD-0100).
        Request.Options batchOptions = new Request.Options(api.connectTimeout(), api.batchReadTimeout(), true);
        for (int attempt = 1; attempt <= MAX_PAGE_ATTEMPTS; attempt++) {
            try {
                MedicineApiResponse response = medicineApiClient.fetchAllMedicines(
                        api.serviceKey(),
                        CHUNK_SIZE,
                        page,
                        "json",
                        batchOptions
                );
                List<MedicineApiResponse.MedicineItem> items = extractItems(response);
                if (items.isEmpty()) {
                    return new PageSyncResult(0, 0);
                }
                List<?> saved = medicineUpsertService.upsertMedicines(items);
                return new PageSyncResult(items.size(), saved.size());
            } catch (Exception e) {
                // Feign 예외 메시지에는 serviceKey가 담긴 요청 URL이 들어 있어 예외 종류만 남긴다.
                log.warn("의약품 동기화 페이지 실패 - page: {}, attempt: {}/{}, errorType: {}",
                        page, attempt, MAX_PAGE_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt == MAX_PAGE_ATTEMPTS) {
                    break;
                }
                Thread.sleep(retryDelayMs);
                retryDelayMs *= 2;
            }
        }
        return null;
    }

    private List<MedicineApiResponse.MedicineItem> extractItems(MedicineApiResponse response) {
        if (response == null || response.body() == null) {
            throw new IllegalStateException("의약품 API 응답이 비어있습니다.");
        }
        if (response.body().items() != null) {
            return response.body().items();
        }
        if (response.body().item() != null) {
            return response.body().item();
        }
        return List.of();
    }

    private record PageSyncResult(int fetchedCount, int insertedCount) {
    }
}
