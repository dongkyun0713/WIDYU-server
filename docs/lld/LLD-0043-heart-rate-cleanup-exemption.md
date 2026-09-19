# LLD-0043: 실증 참가자 심박 이벤트의 30일 자동 삭제 제외

> Low-Level Design. 이 문서는 해당 기능 구현과 PR 본문의 **오라클(ground truth)** 이다.

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #637 |
| 관련 ADR | - |
| 작성자 | Claude |
| 작성일 | 2026-09-19 |

## 1. 목적 / 배경

`HeartRateCleanupScheduler`가 매일 03:00에 30일 지난 `heart_rate_event`를 참가자 구분 없이 지운다. 이대로 실증을 돌리면 첫날 자료가 한 달 뒤 사라진다. 작업지시서 v2 B11·L5가 이 작업을 최우선으로 지정했고, 정책서 B 1.5.2는 「식별 원본은 기존 심박 30일 삭제 스케줄러의 삭제 대상에서 제외한다」로 확정돼 있다.

보존 기간 값은 미정이다. 필요한 것은 기간이 아니라 **지워지지 않게 하는 스위치**다. 연구 참여 등록 모델(#617)은 폐기됐고 대체 모델(작업지시서 질의사항 ①)은 미정이므로, 스키마를 바꾸지 않는 설정값으로 스위치를 둔다.

## 2. 범위

### In scope
- 변경 모듈: widyu-api
- `heart.cleanup.exempt-member-ids` 설정값과 바인딩
- `HeartRateCleanupScheduler`·`HeartRateEventRepository` 삭제 쿼리에 제외 조건 추가

### Out of scope
- 보존 기간 값 변경 (미정)
- `heart_rate_emergency` (원래 삭제 대상이 아니다)
- `sensor_batch` (정리 스케줄러가 없어 이미 지워지지 않는다)
- 회원·회차 모델 기반 제외 (질의사항 ① 확정 뒤 교체)
- 내보내기(B9)

## 3. 인터페이스 / API

API 변경 없음.

설정 (`application-heart.yml` 신규, `application.yml`의 `spring.profiles.include`에 `heart` 추가):

```yaml
heart:
  cleanup:
    exempt-member-ids: ${HEART_CLEANUP_EXEMPT_MEMBER_IDS:}
```

환경변수는 쉼표 구분 회원 ID 목록이다(예: `1023,1077`). 애플리케이션 기본값이 비어 있으면 제외 없음으로 동작하지만, 실증 운영 배포 검증기는 빈 값을 허용하지 않는다.

## 4. 데이터 모델

DB 변경 없음.

`com.widyu.global.properties.HeartProperties` (기존 `AiProperties` 패턴, `@ConfigurationPropertiesScan` 전역 등록):

```java
@ConfigurationProperties(prefix = "heart")
public record HeartProperties(Cleanup cleanup) {
    public record Cleanup(List<Long> exemptMemberIds) {}
}
```

Spring Boot는 쉼표 구분 문자열을 `List<Long>`으로 바인딩한다. 빈 문자열은 빈 리스트다.

## 5. 처리 흐름

1. `HeartRateCleanupScheduler.deleteOldHeartRateEvents()`가 `HeartProperties`에서 제외 목록을 읽는다.
2. 목록이 비어 있으면 기존 `deleteByMeasuredAtBefore(cutoff)`를 그대로 호출한다.
3. 목록이 있으면 `deleteByMeasuredAtBeforeAndMemberIdNotIn(cutoff, ids)`를 호출한다. JPQL: `DELETE FROM HeartRateEvent h WHERE h.measuredAt < :before AND h.member.id NOT IN :memberIds`.
4. 로그는 삭제 건수와 **제외 회원 수**만 남긴다. 회원 ID는 남기지 않는다.

트랜잭션 경계는 기존과 같다(`@Transactional` 스케줄러 메서드).

`NOT IN`에 빈 리스트를 넘기면 JPQL이 깨지므로 2단계 분기가 필수다. 삼항 금지, early return.

## 6. 예외 / 에러 처리

신규 에러 코드 없음. 설정값에 숫자가 아닌 원소가 있으면 Spring Boot 바인딩 오류로 애플리케이션 기동이 실패한다. 운영 배포 검증기는 컨테이너 교체 전에 비어 있거나 양의 `long` 범위를 벗어난 ID를 거부한다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 제외 목록에 든 회원의 31일 전 심박 이벤트는 스케줄러 실행 뒤에도 남고, 목록에 없는 회원의 31일 전 이벤트는 삭제된다.
- [ ] 제외 목록이 비어 있으면 기존 쿼리가 그대로 호출되고 동작이 바뀌지 않는다.
- [ ] 로그 메시지에 회원 ID가 포함되지 않는다.
- [ ] 운영 Compose가 `HEART_CLEANUP_EXEMPT_MEMBER_IDS`를 컨테이너에 전달하고, 배포 검증기가 누락·빈 값·잘못된 ID를 거부한다.
- [ ] `bash scripts/harness/run-module-tests.sh`가 통과한다.

## 8. 영향 범위 / 마이그레이션

DB 마이그레이션 없음. `docker-compose.prod.yml`이 실증 서버의 `HEART_CLEANUP_EXEMPT_MEMBER_IDS`를 컨테이너에 전달한다. 운영 배포에서는 비어 있으면 `validate-prod.py`가 교체 전에 중단하고, local/dev에서 값이 없으면 기존처럼 제외 없음으로 동작한다.

## 9. 미결정 사항 (Open Questions)

- 없음. 질의사항 ①이 정해지면 이 설정값을 그 모델 기반 조건으로 교체한다(이 문서를 Superseded로).

## 10. 참고

- 작업지시서 v2 B11·L5, 정책서 v1.1 B 1.5.2
- `HeartRateCleanupScheduler`, `HeartRateEventRepository.deleteByMeasuredAtBefore`
