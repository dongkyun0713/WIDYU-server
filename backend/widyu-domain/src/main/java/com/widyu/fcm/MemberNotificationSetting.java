package com.widyu.fcm;

import com.widyu.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(uniqueConstraints = {
        @UniqueConstraint(columnNames = {"member_id", "category"})
})
public class MemberNotificationSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private PushSettingGroup category;

    @Column(nullable = false)
    private boolean enabled;

    @Builder(access = AccessLevel.PRIVATE)
    private MemberNotificationSetting(Member member, PushSettingGroup category, boolean enabled) {
        this.member = member;
        this.category = category;
        this.enabled = enabled;
    }

    public static MemberNotificationSetting create(Member member, PushSettingGroup category, boolean enabled) {
        if (category == null || category == PushSettingGroup.NONE) {
            throw new IllegalArgumentException("저장 가능한 푸시 설정 그룹이 아닙니다.");
        }
        return MemberNotificationSetting.builder()
                .member(member)
                .category(category)
                .enabled(enabled)
                .build();
    }

    public void updateEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
