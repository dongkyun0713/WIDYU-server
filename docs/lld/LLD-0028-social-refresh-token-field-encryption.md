# LLD-0028: SocialAccount refreshToken 필드 암호화

| 항목 | 값 |
| --- | --- |
| 상태 | Approved |
| Issue | #579, #583 |
| 관련 ADR | ADR-0025 |
| 작성일 | 2026-09-12 |

## 1. 목적 / 배경

OAuth 리프레시 토큰(`SocialAccount.refreshToken`)이 DB에 평문 저장되어, DB 접근 권한 탈취 시 노출된다. ADR-0025 ②에 따라 이 필드를 저장 시 암호화한다. 개인정보는 실패 비용이 큰 영역이므로, 키 관리·복호화 실패·기존 평문 마이그레이션 정책을 본 LLD로 고정한다.

## 2. 범위

### In scope
- `SocialAccount.refreshToken` 저장 시 AES-256-GCM 암호화 및 조회 시 복호화
- 키 주입·검증, 기존 평문 데이터 호환, 손상 입력 예외 처리

### Out of scope
- 값 기반 조회 대상 식별자 필드(phone·email·name·oauthId) 암호화 (ADR-0025 ③, 보류)
- 저장소/디스크 암호화(RDS at-rest·백업) (ADR-0025 ①, 인프라)
- 키 관리 시스템(KMS) 도입

## 3. 인터페이스 / 구현

- `AesGcmStringConverter`(widyu-domain, `jakarta.persistence.AttributeConverter<String,String>`, Spring `@Component`).
- `@Convert(converter = AesGcmStringConverter.class)`를 `SocialAccount.refreshToken`에 명시 적용(autoApply=false).
- API·DTO 변경 없음. 리프레시 토큰은 값으로 조회되지 않으므로 쿼리 영향 없음.

## 4. 데이터 모델

- `refresh_token` 컬럼 길이 `1000 → 2048` (암호문·Base64 확장 대비).
- 저장 포맷: `enc:v1:` + Base64( IV(12B) ‖ ciphertext ‖ GCM tag(16B) ). 접두사는 암호화 여부 판별과 향후 버전 구분(`enc:v2:` 등)에 사용한다.

## 5. 처리 흐름

1. 저장(`convertToDatabaseColumn`): 값마다 12바이트 랜덤 IV 생성 → AES-256-GCM 암호화 → `enc:v1:<base64>`로 저장. null은 null.
2. 조회(`convertToEntityAttribute`): 접두사가 없으면 기존 평문으로 간주해 그대로 반환, 있으면 IV 분리 후 복호화. null은 null.

## 6. 예외 / 에러 처리

- 키 미설정·32바이트 아님: 빈 생성 시 `IllegalStateException`으로 기동 실패(fail-fast).
- 복호화 실패·손상된 암호문·잘못된 Base64·버퍼 부족: `IllegalStateException`으로 일관 변환한다. 예외 메시지·로그에 원문·암호문·키를 남기지 않는다.

## 7. 키 관리 / 교체

- 키는 `widyu.encryption.aes-key`(Base64 32바이트)로 주입한다. 운영·개발은 `WIDYU_ENCRYPTION_AES_KEY` 환경변수 필수, test 프로파일은 전용 키로 분리하며 운영 키를 저장소에 커밋하지 않는다.
- **키는 고정한다.** 키를 교체하면 기존 `enc:v1:` 암호문을 복호화할 수 없다. 교체가 필요하면 새 키로 전체 재암호화(읽기→평문→새 키 저장) 마이그레이션을 수행한 뒤 교체한다. 현재는 단일 키를 사용하며, 향후 버전 접두사와 KMS 기반 키 롤오버를 검토한다.

## 7. 인수조건 (Acceptance Criteria)

- [ ] 암호화 후 복호화 시 원문이 복원된다.
- [ ] 같은 값도 매번 다른 암호문이 생성된다(랜덤 IV).
- [ ] 접두사 없는 기존 평문은 그대로 조회된다(점진 전환).
- [ ] 손상된 암호문·잘못된 Base64는 `IllegalStateException`으로 처리된다.
- [ ] refreshToken을 조건으로 하는 조회가 없어 기존 로그인·연동 흐름에 회귀가 없다.
- [ ] `./gradlew :backend:widyu-api:test`가 통과한다.

## 8. 영향 범위 / 마이그레이션

- 기존 저장분은 접두사가 없어 그대로 읽히고, 재저장(재로그인·토큰 갱신) 시점부터 암호화되어 점진 전환된다. 강제 백필(전체 재암호화)은 선택 사항이며 필요 시 별도 배치로 수행한다.
- `refresh_token` 길이 확대는 `ddl-auto: update`의 컬럼 확장으로 반영된다.

## 9. 미결정 사항 (Open Questions)

- 기존 평문 refreshToken의 강제 백필 시점·방식(즉시 배치 vs 자연 전환).
- KMS·키 버전(`enc:v2:`) 도입 여부와 시점.
