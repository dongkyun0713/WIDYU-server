# LLD-0100: 공공 약품 API 타임아웃과 검색 트랜잭션 분리

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved (리뷰에서 확정된 결함 수정, 구현 지시 2026-10-22) |
| Issue | #815 |
| 관련 ADR | ADR-0006(약품 검색 전략), ADR-0016(PG 호출 트랜잭션 분리 — 타임아웃 지정 방식 참고) |
| 작성자 | Claude |
| 작성일 | 2026-10-22 |

## 1. 목적 / 배경

LLD-0005는 "외부 API 장애가 서비스 전체로 전파되지 않는다"를 목표로 DB 우선 조회와 공공 API fallback을 둔다. 기존 구현은 두 가지 이유로 이 목표를 지키지 못했다.

- `MedicineApiClientConfig`에 `Request.Options`가 없고 설정에도 Feign 타임아웃이 없어 OpenFeign 기본값(연결 10초, 읽기 60초)이 적용됐다. `searchAndSaveMedicines`가 `@Transactional` 안에서 공공 API를 부르므로 요청 하나가 최대 약 70초 동안 DB 커넥션(Hikari 기본 풀 10개)을 잡았다. 느린 공공 API 몇 건으로 약품과 무관한 API까지 커넥션을 기다린다.
- `upsertMedicines`가 같은 클래스 호출이라 검색 트랜잭션에 참여했다. `item_seq` 유일 제약 위반이 나면 같은 세션에서 재조회가 자동 flush로 다시 실패하거나(`AssertionFailure: null id ... don't flush the Session after an exception occurs`), 커밋 시점에 rollback-only로 `UnexpectedRollbackException`이 났다. 어느 쪽이든 500이다. LLD-0005 7절의 "중복 INSERT 예외 발생 시 DB를 재조회해 정상 응답을 반환한다"가 실제로는 성립하지 않았다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api (goal/medicineschedule, global/properties)
- 공공 약품 API 연결·읽기 타임아웃 설정 키, 배치 전용 읽기 타임아웃
- 검색 흐름의 트랜잭션 분리: 검색 메서드 무트랜잭션, 신규 저장은 별도 빈 `MedicineUpsertService`의 트랜잭션
- 저장 후 응답을 API 응답의 `itemSeq`로 재조회해 구성 (유일 제약 충돌·존재 확인 뒤 경합 모두 같은 경로)
- 약품 API 실패 로그에서 Feign 예외 메시지 제거(예외 종류만)

### Out of scope
- OSIV(`spring.jpa.open-in-view`) 해제 또는 경로 제외 — 9절
- 서킷 브레이커, 재시도 (LLD-0005 Out of scope 유지)
- 주소 검색(`AddressSearchService`)의 `confmKey` 로그 노출 — 별도 이슈
- DB 검색 쿼리(FULLTEXT·prefix LIKE)와 API 계약 변경

## 3. 인터페이스 / API

HTTP API는 바뀌지 않는다(`GET /api/v1/goals/medicine-schedules/search?keyword=`).

설정 키 (`application-medicine.yml`):

| 키 | 값 | 용도 |
| --- | --- | --- |
| `medicine.api.connect-timeout` | `2s` | 검색·배치 공통 연결 타임아웃 |
| `medicine.api.read-timeout` | `3s` | 사용자 검색 읽기 타임아웃 (`MedicineApiClientConfig`의 `Request.Options` 빈) |
| `medicine.api.batch-read-timeout` | `30s` | 월간 배치 페이지 읽기 타임아웃 (호출마다 `Request.Options` 인자로 전달) |

`MedicineApiClient.fetchAllMedicines`는 마지막 인자로 `feign.Request.Options`를 받는다. Feign은 이 타입의 인자를 쿼리·본문으로 쓰지 않고 그 호출의 타임아웃으로 쓴다.

값의 근거:
- 연결 2초: 결제 클라이언트(`PaymentFeignConfig`)와 같다. 정상 TCP 연결은 수십 ms이고, SYN 재전송 1회(1초)를 흡수한다.
- 검색 읽기 3초: 검색은 DB에 결과가 없을 때만 공공 API를 부르고, 실패하면 빈 목록을 돌려준다. 사용자 대기 상한을 연결 포함 약 5초로 둔다. 공공 API 응답 시간 실측값은 없다(9절).
- 배치 읽기 30초: 한 페이지 100건이라 검색보다 응답이 크다. 배치는 웹 요청이 아니라 OSIV가 없고 트랜잭션 밖에서 호출하므로 대기 중 DB 커넥션을 잡지 않는다. 기본값(60초)보다 짧게 두되, 실측 없이 검색 값(3초)을 그대로 쓰면 정상 페이지를 끊어 월간 보충이 중단될 수 있어 따로 둔다. 페이지당 최대 3회 재시도는 그대로다.

## 4. 데이터 모델

변경 없음. `MedicineRepository`에 `findByItemSeqIn(Collection<String>)` 조회 메서드만 추가한다(`item_seq` 유일 인덱스 사용).

## 5. 처리 흐름

### 5-1. 검색 (`ExternalMedicineService.searchAndSaveMedicines`, 트랜잭션 없음)

```
1. DB 검색 (2글자 미만 prefix LIKE, 이상 FULLTEXT) — 저장소 쿼리 단위로 실행
2. 결과가 있으면 반환
3. 공공 API 호출 (검색 타임아웃) — 활성 트랜잭션 없음
   - 예외(타임아웃·연결 실패·5xx·파싱 실패) → errorType만 ERROR 로그, 빈 목록 반환
   - 응답 null/body null/items empty → 빈 목록 반환
4. MedicineUpsertService.upsertMedicines(apiItems) — 별도 빈의 @Transactional
   - 기존 itemSeq 제외, 응답 내부 중복 제외, 신규만 saveAll (LLD-0005와 같음)
   - DataIntegrityViolationException → 이 트랜잭션만 롤백. WARN 후 5로 진행
   - 그 밖의 DB 예외 → 그대로 전파(500). 빈 목록으로 숨기지 않는다
5. findByItemSeqIn(API 응답의 itemSeq) 결과로 응답
```

5에서 재조회하는 이유: 다른 요청이 같은 약품을 저장하는 시점은 두 가지다. 존재 확인 **뒤**에 커밋하면 4에서 유일 제약 충돌이 나고, 존재 확인 **앞**에 커밋하면 4는 그 약품을 건너뛴다. 기존 구현은 두 번째 경우 "새로 저장한 것"만 반환해 빈 목록을 냈다. API 응답의 `itemSeq`로 다시 읽으면 두 경우 모두 그 약품을 돌려준다. 충돌 재조회를 FULLTEXT 키워드 검색에서 `itemSeq` 조회로 바꾼 것도 같은 이유다(FULLTEXT는 API가 찾은 약품과 다른 행을 찾거나 놓칠 수 있다).

### 5-2. OSIV와의 관계

운영 프로필은 OSIV가 켜져 있다(LLD-0064 8절 전제). Spring의 `HibernateJpaVendorAdapter`는 Hibernate 연결 처리 방식을 `DELAYED_ACQUISITION_AND_HOLD`로 두므로, OSIV가 요청 범위 EntityManager를 열어 두면 **1의 DB 조회에서 얻은 커넥션은 응답이 끝날 때까지 반환되지 않는다.** 트랜잭션 분리만으로는 운영에서 외부 호출 동안 커넥션이 풀로 돌아가지 않는다.

따라서 운영에서 커넥션 점유 상한을 줄이는 것은 3절의 타임아웃이다(검색 미스 1건당 최대 약 70초 → 약 5초). 트랜잭션 분리는 (1) 유일 제약 충돌 500 해소, (2) OSIV가 꺼진 dev·local과 향후 OSIV 해제 시 외부 호출 중 커넥션 반환을 담당한다.

OSIV에서 4가 롤백되면 `JpaTransactionManager`가 요청 범위 EntityManager를 `clear()`하므로, 실패한 INSERT의 엔티티가 남아 5의 재조회를 깨지 않는다.

### 5-3. 월간 배치 (`MedicineSyncScheduler`)

LLD-0005 5-3과 같다. 차이는 (1) 페이지 호출에 배치 읽기 타임아웃을 넘기고, (2) 저장을 `MedicineUpsertService`로 하며, (3) 실패 로그에 `e.getMessage()` 대신 예외 종류만 남긴다. Feign 예외 메시지에는 `serviceKey`가 들어 있는 요청 URL이 포함된다.

## 6. 예외 / 에러 처리

| 상황 | 처리 |
|------|------|
| 공공 API 연결·읽기 타임아웃, 연결 실패, 4xx/5xx, 파싱 실패 | `log.error(errorType)` 후 빈 목록 (200) |
| 공공 API 응답 null/body null/items empty | 빈 목록 (200) |
| 신규 저장 `DataIntegrityViolationException` | 저장 트랜잭션만 롤백, `itemSeq` 재조회 결과 반환 (200) |
| 신규 저장 중 그 밖의 DB 예외 | 전파 → `GlobalExceptionHandler` (5xx). 기존에는 `catch (Exception)`이 빈 목록으로 바꾸려다 커밋 시점에 500이 됐다 |
| 배치 페이지 실패 | 기존과 같이 최대 3회 재시도, 로그는 `errorType`만 |

## 7. 인수조건 (Acceptance Criteria)

- [x] 검색 호출은 설정한 읽기 타임아웃(테스트 300ms)을 넘기면 서버 응답(1초)을 기다리지 않고 실패한다 — `MedicineApiClientTimeoutTest`
- [x] 배치 호출은 검색 읽기 타임아웃보다 늦은 응답도 배치 읽기 타임아웃 안이면 받아 저장한다 — `MedicineApiClientTimeoutTest`, `MedicineSyncSchedulerTest`
- [x] 공공 API 호출 중 활성 DB 트랜잭션이 없다 — `MedicineSearchFallbackIntegrationTest`
- [x] 존재 확인 뒤 다른 요청이 같은 `itemSeq`를 저장해 유일 제약과 충돌해도 저장된 약품을 반환한다 (OSIV 유무 각각) — `MedicineSearchFallbackIntegrationTest`
- [x] 존재 확인 전에 다른 요청이 같은 `itemSeq`를 저장했으면 그 약품을 반환한다 — `MedicineSearchFallbackIntegrationTest`
- [x] 공공 API 읽기 타임아웃이면 빈 목록을 반환하고 로그에 `serviceKey`가 없다 — `MedicineSearchFallbackIntegrationTest`
- [x] 유일 제약 외의 DB 예외는 빈 목록으로 숨기지 않고 전파한다 — `MedicineSearchFallbackIntegrationTest`
- [x] 배치 실패 로그에 Feign 예외 메시지(요청 URL)를 남기지 않는다 — 코드 확인
- [x] LLD-0005 7절의 기존 인수조건(DB 우선, FULLTEXT/prefix, 신규만 저장, 응답 내부 중복 1회 저장, 배치 청크·재시도)을 유지한다

## 8. 영향 범위 / 마이그레이션

- DB 변경 없음. 배포 시 설정 키 3개가 `application-medicine.yml`에 함께 배포된다. 환경변수 `MEDICINE_API_READ_TIMEOUT` 등으로 덮어쓸 수 있다(Spring 완화 바인딩).
- 공공 API가 3초 넘게 응답하는 검색은 이제 빈 목록이 된다. 같은 검색어의 다음 요청은 다시 공공 API를 부른다.
- 검색 미스 응답은 "새로 저장한 약품"이 아니라 "API가 찾은 약품 중 DB에 있는 것 전체"다. 이미 저장돼 있었지만 DB 검색에서 빠진 약품도 이제 응답에 포함된다. 응답 순서는 DB 조회 순서다(DB 검색 경로도 순서를 보장하지 않는다).
- `ExternalMedicineService.upsertMedicines`는 `MedicineUpsertService.upsertMedicines`로 옮겼다.

## 9. 미결정 사항 (Open Questions)

- [ ] 운영 OSIV를 끄거나 약품 검색 경로를 OSIV에서 제외할지. 켜져 있는 동안 검색 미스 1건은 타임아웃 상한(약 5초)만큼 커넥션을 잡는다. OSIV 해제는 LLD-0064처럼 OSIV를 전제한 코드가 있어 전역 영향 검토가 필요하다. 같은 이유로 결제 PG 호출(LLD-0022)도 운영에서는 호출 중 커넥션을 반환하지 않는다.
- [ ] 검색 3초·배치 30초는 공공 API 응답 시간 실측 없이 정한 초기값이다. 운영 로그(외부 API 호출 소요)나 실측으로 p99를 확인해 조정한다.

## 10. 참고

- LLD-0005 (약품 검색 DB 우선 조회와 외부 API fallback), LLD-0022 (PG 호출 트랜잭션 분리), LLD-0064 8절 (운영 OSIV 전제)
- `spring-orm` `HibernateJpaVendorAdapter`: `prepareConnection`(기본 true)이면 `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_HOLD`
- Feign `SynchronousMethodHandler.findOptions`: 메서드 인자 중 `Request.Options`를 그 호출의 타임아웃으로 사용
