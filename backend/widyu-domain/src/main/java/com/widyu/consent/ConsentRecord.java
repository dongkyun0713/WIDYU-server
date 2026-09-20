package com.widyu.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 한 회원이 한 항목에 대해 한 번 밝힌 의사(동의 또는 철회) 1건(ADR-0036 결정 1, LLD-0055 4절).
 *
 * <p><b>추가 전용</b>이다. UPDATE·DELETE가 없으므로 이 테이블이 곧 이력이고 현재 상태는
 * 항목별 최신 행이다. 철회도 지우는 것이 아니라 {@code granted=false} 행을 새로 남긴다.
 *
 * <p>enum 두 컬럼은 {@code @JdbcTypeCode(SqlTypes.VARCHAR)}로 못박는다. Hibernate 6은 MySQL에서
 * {@code @Enumerated(STRING)}을 native {@code ENUM(...)}으로 매핑하는데, 운영 DDL은 VARCHAR라
 * 그대로 두면 {@code ddl-auto: validate}가 어긋난다. VARCHAR면 값이 늘어도 ALTER가 필요 없다.
 *
 * <p>{@code BaseTimeEntity}를 상속하지 않는다. 행이 불변이라 {@code updated_at}이 의미가 없고,
 * 「언제 밝힌 의사인가」는 {@code recorded_at} 하나로 충분하다.
 */
@Entity
@Getter
@Table(
        name = "consent_record",
        indexes = @Index(
                name = "idx_consent_record_member_key_time",
                columnList = "member_id, consent_key, recorded_at")
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "consent_record_id")
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "consent_key", nullable = false, length = 40)
    private ConsentKey consentKey;

    /** 앱이 보여 준 동의문의 판. 철회 행은 직전 행의 판을 그대로 복사한다. */
    @Column(name = "version", nullable = false, length = 32)
    private String version;

    /** false는 철회 또는 미동의를 뜻한다. */
    @Column(name = "granted", nullable = false)
    private boolean granted;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "source", nullable = false, length = 16)
    private ConsentSource source;

    private ConsentRecord(
            Long memberId,
            ConsentKey consentKey,
            String version,
            boolean granted,
            LocalDateTime recordedAt,
            ConsentSource source
    ) {
        this.memberId = memberId;
        this.consentKey = consentKey;
        this.version = version;
        this.granted = granted;
        this.recordedAt = recordedAt;
        this.source = source;
    }

    public static ConsentRecord of(
            Long memberId,
            ConsentKey consentKey,
            String version,
            boolean granted,
            LocalDateTime recordedAt,
            ConsentSource source
    ) {
        return new ConsentRecord(memberId, consentKey, version, granted, recordedAt, source);
    }
}
