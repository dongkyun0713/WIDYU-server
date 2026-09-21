package com.widyu.location.access;

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
 * 보호자가 시니어 위치를 한 번 읽은 사실 1건(위치정보법 제16조②, ADR-0036 결정 3, LLD-0056 4절).
 *
 * <p><b>추가 전용</b>이다. 행을 지우거나 고치지 않고, 통보를 보낸 시각을 남기는
 * {@link #markNotified}만 값을 바꾼다. 자동 삭제는 없다(ADR-0036 결정 6).
 *
 * <p><b>좌표를 담지 않는다.</b> 법이 요구하는 것은 「언제 누가 봤는가」이지 「무엇을 봤는가」가
 * 아니고, 좌표를 복제하면 지워야 할 개인정보가 한 벌 더 생긴다.
 *
 * <p>{@code BaseTimeEntity}를 상속하지 않는다. 열람 시각은 {@code accessed_at} 하나로 충분하다.
 */
@Entity
@Getter
@Table(
        name = "location_access_log",
        indexes = {
                @Index(
                        name = "idx_location_access_log_senior_time",
                        columnList = "senior_member_id, accessed_at"),
                @Index(
                        name = "idx_location_access_log_senior_notified",
                        columnList = "senior_member_id, notified_at")
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LocationAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "location_access_log_id")
    private Long id;

    /** 위치를 본 보호자. 시니어 본인 조회는 아예 행을 만들지 않는다. */
    @Column(name = "viewer_member_id", nullable = false)
    private Long viewerMemberId;

    /** 위치가 조회된 시니어. */
    @Column(name = "senior_member_id", nullable = false)
    private Long seniorMemberId;

    // Hibernate 6은 MySQL에서 @Enumerated(STRING)을 native ENUM으로 매핑한다. 운영 DDL은
    // VARCHAR이므로 명시하지 않으면 ddl-auto: validate가 어긋나고, 경로가 늘 때마다 ALTER가 필요해진다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "path", nullable = false, length = 20)
    private LocationAccessPath path;

    @Column(name = "accessed_at", nullable = false)
    private LocalDateTime accessedAt;

    /** 통보 FCM을 넣은 시각. null은 「아직 알리지 않았다」이고 다음 다이제스트의 대상이다. */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    private LocationAccessLog(
            Long viewerMemberId,
            Long seniorMemberId,
            LocationAccessPath path,
            LocalDateTime accessedAt
    ) {
        this.viewerMemberId = viewerMemberId;
        this.seniorMemberId = seniorMemberId;
        this.path = path;
        this.accessedAt = accessedAt;
    }

    public static LocationAccessLog of(
            Long viewerMemberId,
            Long seniorMemberId,
            LocationAccessPath path,
            LocalDateTime accessedAt
    ) {
        return new LocationAccessLog(viewerMemberId, seniorMemberId, path, accessedAt);
    }

    public void markNotified(LocalDateTime notifiedAt) {
        this.notifiedAt = notifiedAt;
    }
}
