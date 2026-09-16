# ADR-0026: 국내 실증(IRB) 2층 보존과 제자리 가명화

> Architecture Decision Record. 하나의 중요한 의사결정과 그 이유를 기록한다.

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted |
| 날짜 | 2026-09-16 |
| 관련 | LLD-0031, #617, `apiDocs/위듀_데이터정책서_v0.md` 1.5.1~1.5.3 |

## 맥락 (Context)

데이터 정책서 v0은 국내 실증(IRB)의 보존을 두 층으로 확정했다. 식별 가능한 원본은 `identified_until`까지 보존하고, `pseudonymized_at`에 직접식별자와 연결키를 분리한 가명 연구본으로 전환하며, `research_until`에 파기한다. 보존 정책은 앱 빌드나 locale이 아니라 서버에 등록된 `study_id + participation_id`가 정한다.

현재 서버에는 연구 참여 모델이 없고, `HeartRateCleanupScheduler`가 30일 지난 심박 이벤트를 참여자 구분 없이 지운다. 재식별 키를 데이터와 분리해 저장하라는 요구도 있다.

결정할 것은 두 가지다. 보존 정책을 어디에 저장할지, 그리고 가명화를 어떤 방식으로 구현할지.

## 결정 (Decision)

1. **보존 정책은 `study_participation` 행이 정한다.** `study_id`, `participation_id`(unique), `member_id`, `data_policy`, `identified_until`, `pseudonymized_at`, `research_until`, `consent_version`, `status`를 가진다. 등록·변경은 Admin 전용 API로만 하고, 앱이 보낸 정책 값은 검증용으로만 쓴다. 기간 변경 이력은 새 테이블 대신 기존 `AdminAuditLog`에 `STUDY_PARTICIPATION_PERIOD_CHANGE` 액션으로 남긴다.
2. **가명화는 복사가 아니라 제자리 분리로 한다.** 심박 이벤트·위급 기록에 `study_participation_id` FK를 두고 수집 시 채운다. 가명화 시점에 해당 행의 `member_id`와 주소(`location`)를 null로 바꾼다. 데이터 행은 `study_participation_id`로만 식별되고, 재식별 키는 `study_participation.member_id` 하나만 남는다. 파기 시점에 데이터 행을 삭제하고 `study_participation.member_id`도 null 처리한다. 참여 행 자체는 파기 증적으로 남긴다.
3. **연구 참여 행은 30일 삭제 스케줄러에서 제외한다.** `study_participation_id`가 null인 행만 30일 삭제 대상이다.

FK 추가와 스케줄러는 LLD-0032·0033에서 구현한다. 이 ADR은 모델과 방식만 정한다.

## 고려한 대안 (Considered Options)

1. **별도 연구 테이블로 복사** — 가명화 시점에 `study_heart_rate_sample` 같은 테이블로 복사하고 원본을 삭제한다. 장점은 연구본과 운영본의 테이블이 물리적으로 갈려 DB 권한 분리가 쉽다. 단점은 엔티티·매핑·삭제 코드가 두 벌이고, 복사 중 실패 시 원본·사본 정합을 맞추는 보상 로직이 필요하다. 권한 분리는 결국 DB 롤로 해야 하므로 테이블 분리만으로 얻는 이득이 작다.
2. **제자리 분리(채택)** — 기존 테이블에 FK 하나를 더하고 가명화는 bulk update 한 번이다. 기존 조회는 전부 `member_id` 조건이라 가명화된 행이 자연히 제외된다. 단점은 `member_id`가 nullable이 되고, 같은 테이블에 운영 행과 연구 행이 섞인다.
3. **참여 모델 없이 회원 단위 플래그** — `Member`에 보존 종료일을 두는 방식. 회원이 여러 연구에 순차 참여하거나 동의 버전이 바뀌면 이력이 사라지므로 기각한다.

## 결과 (Consequences)

### 긍정
- 정책서 1.5.1~1.5.3의 필수 필드가 서버 모델에 1:1로 대응한다.
- 가명화·파기가 각각 bulk update·delete 한 번이라 실패 지점이 적다.
- 기존 심박 조회 API를 바꾸지 않아도 가명화 행이 노출되지 않는다.

### 부정 / 트레이드오프
- `heart_rate_event.member_id`, `heart_rate_emergency.member_id`가 nullable이 된다(LLD-0033). 두 컬럼을 non-null로 가정한 코드가 생기지 않게 주의한다.
- 재식별 키(`study_participation.member_id`)의 접근 권한 분리는 DB 롤 작업이며 이 ADR로 해결되지 않는다. 운영 과제로 남긴다.
- 회원당 ACTIVE 참여 1개 제약은 MySQL partial unique가 없어 서비스 검증에 의존한다.

## 후속 / 미결정
- LLD-0032: 심박 기록의 연구 귀속과 30일 삭제 제외.
- LLD-0033: 가명화·파기 스케줄러.
- 일본 현장실증(`data_policy=JP_PILOT`)의 D+N 보존과 회차번호 삭제는 정책서 1.5.6과 일본 실증 계획서의 삭제 시점 불일치를 정리한 뒤 별도 ADR로 다룬다.
- 판정 기록(정책서 1.4)은 `run_id` 형식(§3 K2) 확정 뒤 다룬다.
