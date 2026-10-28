# LLD-0005: 약품 검색 DB 우선 조회와 외부 API fallback

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | - |
| 관련 ADR | ADR-0006 (약품 검색 전략) |
| 작성자 | dongkyunKim |
| 작성일 | 2026-07-05 |

## 1. 목적 / 배경

복약 스케줄 생성 전에 사용자는 약품명을 검색해야 한다.
공공 의약품 API 장애가 발생해도 검색 기능 전체가 중단되지 않도록, 자체 DB를 우선 조회하고 결과가 없을 때만 외부 API를 fallback으로 호출한다.
한글 약품명 부분 검색 성능을 위해 FULLTEXT N-gram 인덱스를 사용한다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (goal/medicineschedule), widyu-domain (Medicine)
- 약품 검색 API
- 자체 DB 우선 조회
- 2글자 이상 FULLTEXT 검색, 1글자 prefix LIKE 검색
- 외부 공공 API fallback
- `itemSeq` 기준 중복 제거와 신규 약품 저장
- 공공 API 신규 데이터 backfill 배치

### Out of scope
- 약품 삭제·수정 동기화 정책
- 외부 API 서킷 브레이커
- Redis 분산 락 기반 동시 INSERT 제어
- 복약 스케줄 생성/수정 상세 정책

## 3. 인터페이스 / API

```http
GET /api/v1/goals/medicine-schedules/search?keyword=타이레놀
```

Response:
```json
{
  "isSuccess": true,
  "code": "MEDICINE_2009",
  "message": "약품 검색 성공",
  "data": {
    "medicines": [
      {
        "medicineId": 1,
        "itemName": "타이레놀정500밀리그람",
        "itemImage": "https://...",
        "usage": "복용 방법...",
        "efficacy": "효능..."
      }
    ]
  }
}
```

검색 실패나 외부 API 장애 시에도 현재 구현은 예외 응답 대신 빈 목록을 반환한다.

## 4. 데이터 모델

### 엔티티 (widyu-domain)

**Medicine** (`medicine` 테이블):
```
medicine
├── id (PK, IDENTITY)
├── item_seq (String, unique, length=100)
├── item_name (String, not null, length=200)
├── entp_name (String, length=200)
├── item_image (String, length=500)
├── use_method_qesitm (TEXT)
├── efcy_qesitm (TEXT)
└── createdAt, updatedAt
```

### 인덱스

```sql
FULLTEXT INDEX on medicine(item_name) WITH N-gram parser
```

코드 조회:
```sql
SELECT * FROM medicine
WHERE MATCH(item_name) AGAINST(:keyword IN BOOLEAN MODE)
LIMIT 10
```

1글자 검색:
```sql
SELECT * FROM medicine
WHERE item_name LIKE :prefix
LIMIT 10
```

### 외부 API

공공 API endpoint:
```http
GET {medicine.api.url}/getDrbEasyDrugList
```

검색 파라미터:
- `serviceKey`
- `itemName`
- `numOfRows=10`
- `pageNo=1`
- `type=json`

배치 동기화 파라미터:
- `numOfRows=100`
- `pageNo=1..N`
- `type=json`

## 5. 처리 흐름

> 트랜잭션 경계, 외부 API 타임아웃, 중복 저장 복구 조회는 [LLD-0100](LLD-0100-medicine-api-timeout-transaction-isolation.md)이 대체한다. 검색 메서드는 트랜잭션 없이 실행하고 신규 저장만 `MedicineUpsertService` 트랜잭션에서 한다.

### 5-1. 검색 요청 (`ExternalMedicineService.searchAndSaveMedicines`)

```
1. GET /api/v1/goals/medicine-schedules/search?keyword={keyword}
2. keyword.length < 2:
   - medicineRepository.searchByNamePrefix(keyword + "%")
3. keyword.length >= 2:
   - medicineRepository.searchByNameFullText(keyword)
4. DB 결과가 있으면 MedicineSearchResponse로 반환
5. DB 결과가 없으면 MedicineApiClient.searchMedicines(serviceKey, keyword, 10, 1, "json")
6. 외부 API 응답이 null/body null/items empty이면 빈 목록 반환
7. upsertMedicines(apiItems)
   - itemSeq null 제외
   - findItemSeqsByItemSeqIn(seqs)로 기존 데이터 제외
   - seenSeqs로 응답 내부 중복 제외
   - 신규 Medicine만 saveAll()
8. 저장된 Medicine 목록을 MedicineSearchResponse로 반환
```

트랜잭션: `@Transactional`

### 5-2. 중복 저장 경합 처리

```
1. 같은 itemSeq를 여러 요청이 동시에 저장 시도
2. DB unique 제약으로 DataIntegrityViolationException 발생 가능
3. catch 후 medicineRepository.searchByNameFullText(keyword) 재조회
4. 재조회 결과를 응답으로 반환
```

현재 구현은 INSERT 경쟁을 락으로 막지 않고, DB 제약과 재조회로 흡수한다.

### 5-3. 월간 신규 데이터 backfill (`MedicineSyncScheduler`)

```
@Scheduled(cron = "0 0 3 1 * *")  ← 매월 1일 03:00
  page = 1
  while true:
    1. fetchAllMedicines(serviceKey, 100, page, "json")와 upsert를 한 페이지 작업으로 처리
    2. 페이지 실패 시 1초, 2초 간격으로 최대 3회 재시도
    3. 재시도 소진 → 오류 로그 후 배치 종료
    4. items empty → 종료
    5. 실제 신규 INSERT 건수를 totalSynced에 누적
    6. items.size < 100 → 마지막 페이지로 보고 종료
    7. page++
    8. Thread.sleep(300ms)
```

기존 `itemSeq`는 갱신·삭제하지 않고 건너뛴다. 따라서 이 배치는 전체 데이터 동기화가 아니라 신규 약품을 보충하는 backfill이다. 사용자가 검색한 신규 약품은 DB miss 시 외부 API fallback으로 즉시 저장된다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
|------|------|
| DB 검색 결과 없음 + 외부 API 결과 없음 | 빈 `medicines` 목록 반환 |
| 외부 API 응답 null/body null | 빈 `medicines` 목록 반환 |
| 외부 API 호출/파싱 실패 | log.error 후 빈 목록 반환 |
| `DataIntegrityViolationException` | log.warn 후 FULLTEXT DB 재조회 |
| 배치 페이지 API 응답 없음·저장 실패 | 최대 3회 지수 백오프 재시도 후 log.error와 함께 배치 종료 |

## 7. 인수조건 (Acceptance Criteria)

- [x] DB에 검색 결과가 있으면 외부 API를 호출하지 않는다
- [x] 2글자 이상 키워드는 FULLTEXT `MATCH AGAINST`로 조회한다
- [x] 1글자 키워드는 prefix LIKE로 조회한다
- [x] DB 결과가 없으면 공공 API를 fallback으로 호출한다
- [x] 외부 API 결과는 `itemSeq` 기준으로 신규 데이터만 저장한다
- [x] 외부 API 응답 내부 중복 `itemSeq`는 한 번만 저장한다
- [x] 중복 INSERT 예외 발생 시 DB를 재조회해 정상 응답을 반환한다
- [x] 배치 동기화는 100건 단위 청크와 300ms 호출 간격을 사용한다
- [x] 배치 페이지 실패는 최대 3회 재시도하고, 성공 페이지의 신규 INSERT만 누적한다
- [x] Swagger에 약품 검색 응답이 반영된다

## 8. 영향 범위 / 마이그레이션

- `medicine.item_seq` unique 제약 유지 필요
- `medicine.item_name` FULLTEXT N-gram 인덱스 유지 필요
- 외부 API 장애 시 검색 결과가 빈 목록일 수 있으나, 서비스 전체 예외로 전파하지 않는다
- 성능 기준: `LIKE` 대비 평균 응답 시간 `593ms → 111ms` (수만 건 데이터 SQL 벤치마크 기준)

## 9. 미결정 사항 (Open Questions)

없음. (백필 문서, 구현 완료 상태)

후속 개선 후보: 트래픽 증가 시 외부 API fallback 구간에 서킷 브레이커를 두고, 동시 신규 INSERT에는 Redis 분산 락 도입 여부를 재검토한다.

## 10. 참고

- `ExternalMedicineService.java`: DB 우선 조회, 외부 API fallback, upsert
- `MedicineRepository.java`: FULLTEXT / prefix LIKE 조회
- `MedicineSyncScheduler.java`: 청크 배치 동기화
- `MedicineApiClient.java`: 공공 의약품 API Feign client
- `Medicine.java` (widyu-domain): 약품 엔티티
- `apiDocs/blog/medicine-api-troubleshooting.md`: 장애 대응 및 성능 측정 기록
