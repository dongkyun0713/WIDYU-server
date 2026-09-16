# LLD-0035: 실제 MySQL 결제 장애 검증

| 항목 | 값 |
| --- | --- |
| 상태 | Review |
| Issue | #604 |
| 관련 ADR | ADR-0012, ADR-0016 |
| 작성자 | Codex |
| 작성일 | 2026-09-14 |

## 1. 목적 / 배경

기존 H2·Mockito 검증만으로 MySQL 잠금·커밋·롤백과 PG가 이미 처리한 요청의 복구를 증명할 수 없다. 실제 MySQL과 상태를 보관하는 로컬 HTTP PG 대역으로 결제·취소·포인트를 대조한다.

## 2. 범위

- `widyu-api` 통합테스트와 재현 명령·실측 기록.
- 동시 승인, 동일 부분 취소, 응답 유실, PG 성공 후 DB 반영 오류와 복구.
- 운영 PG·실결제·AWS 변경, FCM 구현, 실제 프로세스 강제 종료는 제외한다.

## 3. 인터페이스 / API

기존 승인·취소·복구 서비스 진입점을 호출한다. 공개 API·Swagger 변경은 없다. PG 대역은 loopback 임의 포트에서 POST 승인·취소와 GET 조회를 제공하고 멱등 키별 최초 결과를 저장한다.

## 4. 데이터 모델

ERD-0001의 PaymentOrder, Payment, PaymentCancel, SeniorProfile, PointHistory를 사용한다. 일회용 DB에 Hibernate로 스키마를 생성한다. 운영 마이그레이션 검증은 아니다.

## 5. 처리 흐름

1. Docker 격리를 우선 조사하고, 불가능하면 별도 데이터 디렉터리·socket·loopback 포트의 로컬 mysqld를 사용한다.
2. 실제 Spring 트랜잭션 프록시와 저장소를 구성한다. 외부 서비스 설정은 로드하지 않는다.
3. PG 응답 직전 barrier로 동시 요청을 겹치게 하고 동일 PG 키와 트랜잭션 종료를 확인한다.
4. PG 처리 후 응답을 유실하거나 결과 반영 SQL에 trigger 오류를 주입하고 중간 DB 상태를 확인한다.
5. 재시도 시각만 과거로 옮긴 뒤 실제 복구 진입점을 실행한다. 재복구·재전송에도 중복 반영이 없는지 확인한다.

## 6. 예외 / 에러 처리

MySQL 테스트는 환경변수로 명시적으로 실행한다. loopback과 전용 DB 이름을 검사하며 H2 대체를 허용하지 않는다. 환경 미준비·실패·skip은 통과와 구분한다. 장애 trigger는 finally에서 제거한다.

## 7. 인수조건 (Acceptance Criteria)

- [x] AC1: 실제 MySQL 제품·버전·격리 수준과 실행 결과를 기록한다.
- [x] AC2: 겹친 승인 두 요청이 동일 PG 키로 결제 1건·적립 1건·10100포인트를 남긴다.
- [x] AC3: 겹친 3000원 부분 취소가 논리 환불 1건·취소 1건·환수 1건·7100포인트를 남긴다.
- [x] AC4: 승인·취소 응답 유실을 PG 조회로 복구하고 재전송에도 중복 반영하지 않는다.
- [x] AC5: 승인·취소 DB 반영 오류에서 원자적 롤백과 복구 후 정합성을 확인한다.
- [x] AC6: harness verify와 review 결과 및 미실행 범위를 기록한다.

## 8. 영향 범위 / 마이그레이션

운영 코드·스키마 변경 없음. 전용 테스트는 일반 H2 테스트와 분리한다. 기존 ADR 계약을 검증하므로 새 ADR은 작성하지 않는다. 운영 MySQL과 실측 버전 차이를 기록한다.

## 9. 미결정 사항 (Open Questions)

없음. LLD-0016의 승인 복구 후속 항목은 LLD-0022를 따른다. LLD-0017의 취소 거래 키 저장 결정은 변경하지 않는다.

## 10. 참고

- [승인](LLD-0016-payment-confirmation-idempotency.md), [부분 취소](LLD-0017-payment-partial-cancel-idempotency.md), [복구](LLD-0022-payment-pg-call-transaction-separation.md)
- 로컬 실측 기록: `apiDocs/api/payments/payment-resilience-measurement-record.md` (Git 제외)

## 11. 실행 증거 (2026-09-14)

Docker 데몬 연결 불가로 격리 컨테이너는 미실행했다. 대신 Homebrew MySQL 9.0.1을 새 임시 datadir/socket과 127.0.0.1:33604에서 실행했다. `--no-defaults`, mysqlx 비활성화, 전용 DB `payment_resilience_604`를 사용했다. 실제 격리 수준은 REPEATABLE-READ(JDBC 4)다.

```bash
# 실행 중인 일회용 loopback MySQL과 전용 DB를 먼저 준비한다.
# DB_USERNAME/DB_PASSWORD는 해당 일회용 인스턴스 전용 환경변수다.
PAYMENT_MYSQL_URL=jdbc:mysql://127.0.0.1:33604/payment_resilience_604 \
./gradlew :backend:widyu-api:paymentMySqlTest
bash scripts/harness/verify.sh
```

Java 21.0.10 사용. MySQL 전용 6개가 실패·오류·skip 없이 통과했다. `paymentMySqlTest`는 일반 `test`에서 분리하며 URL이 없으면 종료 코드 1로 실패한다. 전용 작업은 매번 실행하고 `create-drop`을 사용하므로 전용 일회용 DB만 지정한다.

| 시나리오 | PG HTTP POST 수 / 논리 처리 수 | 최종 포인트 | 본문 실행 시간 |
| --- | --- | --- | --- |
| 동시 승인 | 2 / 승인 1 | 10100 | 44 ms |
| 동시 부분 취소 | 3 / 승인 1·환불 1 | 7100 | 47 ms |
| 승인 응답 유실 | 1 / 승인 1 | 10100 | 34 ms |
| 취소 응답 유실 | 2 / 승인 1·환불 1 | 7100 | 56 ms |
| 승인 DB 롤백 | 1 / 승인 1 | 10100 | 366 ms |
| 취소 DB 롤백 | 2 / 승인 1·환불 1 | 7100 | 83 ms |

취소 행 수·상태·누적 금액, 결제 행 수, 포인트 잔액·이력 수·operation key 고유 수를 커밋 후 JDBC로 검증한다. 복구 두 회 호출은 10~19 ms였다. 대기 시각을 앞당긴 로컬 단일 표본이며 운영 복구 SLA나 성능 기준으로 사용하지 않는다.

XML 증거: `backend/widyu-api/build/test-results/paymentMySqlTest/TEST-com.widyu.pay.integration.PaymentMySqlResilienceTest.xml`. 초기 실행에서 QueryDSL 빈 누락과 테스트의 SQL/JPA 시간대 혼용을 발견해 수정했다. 일반 테스트와 전용 테스트의 보고서 경로는 분리된다.

운영 RDS 버전·마이그레이션, 실제 프로세스 재시작, 실제 PG 인증·오류 코드·멱등 유지 기간, 다중 인스턴스 및 장시간 부하는 미검증이다. FCM과 AWS는 저장소 코드·운영 문서만 읽어 조사했고 실행하지 않았다. 상세 대안·초기 실패·환경·FCM 개선 범위·AWS 준비 항목은 로컬 실측 기록에 남겼다.

최종 `harness verify` 종료 코드 0: compileJava와 API 529개 중 520개 통과, 9개 skip(Redis 부재 6개, 영상 fixture 부재 3개). 신규 Java 두 파일 정적 검사와 `git diff --check` 통과. Domain 소스 변경이 없어 별도 Domain 테스트는 실행하지 않았다.

review 스킬 자체 검수 판정은 **APPROVE**다. AC1~6과 실제 테스트·보고서를 대조했고 운영 코드·API·엔티티 변경은 없다. 테스트는 실제 Spring/JPA 통합 구성을 사용하므로 MockitoExtension 대신 Spring 확장을 사용하고, 인증 대역만 BDDMockito로 구성한다. 사람의 승인·출시 승인을 뜻하지 않는다. 일회용 MySQL은 검증 후 정상 종료했으며 커밋하지 않았다.
