# ADR-0025: 개인정보 저장 시 암호화 정책

| 항목 | 값 |
| --- | --- |
| 상태 | Accepted |
| 날짜 | 2026-09-12 |
| 관련 | #579 |

## 맥락 (Context)

개인정보가 DB(MySQL)와 Redis에 저장된다. 저장 데이터 암호화(at-rest) 범위와 방식을 정하지 않으면, 디스크·백업·스냅샷 유출 시 원문이 노출될 수 있다.

저장되는 민감정보와 값 기반 조회 여부:

| 위치 | 필드 | 값으로 조회 |
| --- | --- | --- |
| `Member` | name, phoneNumber | 예 (`findByPhoneNumber`, `findByPhoneNumberAndName`) |
| `LocalAccount` | email, password | email 예 / password는 BCrypt 해시 |
| `SocialAccount` | email, oauthId, refreshToken | email·oauthId 예 / refreshToken 아니오 |
| Redis(TTL) | phoneNumber, 위치 좌표, 심박(BPM) 등 | - |

**핵심 제약**: phone·email·oauthId·name은 WHERE 조건으로 조회된다. 이 필드를 랜덤 IV 방식으로 암호화하면 동등 비교 조회가 깨진다. 조회를 유지하려면 결정적(deterministic) 암호화나 blind-index가 필요하며, 이는 컨버터 도입과 기존 데이터 백필 마이그레이션을 동반한다.

WIDYU는 주민등록번호 등 법정 의무 암호화 대상 식별자를 수집하지 않는다.

## 결정 (Decision)

3단계로 정의한다.

1. **저장소/전송 암호화 (베이스라인, 필수)** — MySQL(RDS) at-rest 암호화, 백업·스냅샷 암호화, Redis TLS/AUTH 및 네트워크 격리를 적용한다. 디스크·백업 레벨에서 모든 저장 데이터를 보호하며, 제출 문서의 "저장소 암호화" 요구를 충족한다. 대부분 인프라 설정이며 애플리케이션 코드 변경이 거의 없다.
2. **필드 단위 암호화 — `SocialAccount.refreshToken`** — OAuth 리프레시 토큰은 민감 자격증명이고 값으로 조회되지 않으므로 AES-256-GCM(랜덤 IV) + JPA `AttributeConverter`로 암호화한다. 암호화 키는 환경변수/시크릿으로 주입한다. 조회 쿼리에 영향이 없다.
3. **식별자 필드(phone·email·name·oauthId) 암호화 — 현재 보류** — 모두 값 기반 조회 대상이라 결정적 암호화/blind-index가 필요하고, 마이그레이션 규모와 쿼리 회귀 위험이 크다. 베이스라인(1)이 at-rest 요구를 충족하고 법정 의무 식별자(주민번호 등)를 수집하지 않으므로, 법적 요구가 강화되기 전까지 도입하지 않는다.

`password`는 이미 BCrypt 해시로 저장되므로 현행을 유지한다.

Redis 임시 데이터(전화번호·위치·심박)는 짧은 TTL과 TLS/AUTH/네트워크 격리로 보호하고, 민감 키스페이스는 영속화(RDB/AOF) 제외를 검토한다.

## 고려한 대안 (Considered Options)

1. **전 필드 결정적 암호화** — 가장 강력하나, 조회 필드 전반에 blind-index·백필 마이그레이션이 필요해 비용·회귀 위험이 크다. 법정 의무가 없는 현 시점에는 과하다.
2. **저장소 암호화만** — 가장 단순하나, DB 접근 권한이 탈취되면 원문이 노출된다. refreshToken 같은 고위험 자격증명은 추가 보호가 필요하다.
3. **저장소 암호화 + 고위험 필드만 앱 암호화 (채택)** — 베이스라인으로 광범위하게 보호하고, 조회에 영향 없는 refreshToken만 필드 암호화해 비용 대비 효과를 극대화한다.

## 결과 (Consequences)

- 디스크·백업·스냅샷 유출에 대해 at-rest 암호화가 적용된다.
- refreshToken은 DB 접근 수준에서도 원문이 노출되지 않는다(후속 구현).
- 식별자 필드는 당분간 평문으로 DB에 저장되며, 저장소 암호화에 의존한다. 법적 요구 변화 시 본 ADR을 갱신하고 결정적 암호화를 재검토한다.
- 키 관리(주입·교체)는 운영 절차로 관리하며, 향후 KMS 도입을 검토한다.
- refreshToken 필드 암호화는 별도 구현 이슈로 진행한다.
