package com.widyu.medicine;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.entity.Status;
import com.widyu.member.Member;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MedicineSchedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private LocalTime alarmTime;

    @OneToMany(mappedBy = "medicineSchedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<MedicineCategory> categories = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    // 이 버전이 유효한 시작일 (수정 시 새 버전이 생기고, 과거 버전은 그대로 보존된다)
    @Column
    private LocalDate effectiveFrom;

    // 유효 종료일. null이면 현재 유효한 최신 버전
    @Column
    private LocalDate effectiveTo;

    @Builder(access = AccessLevel.PRIVATE)
    private MedicineSchedule(Member member, LocalTime alarmTime, LocalDate effectiveFrom) {
        this.member = member;
        this.alarmTime = alarmTime;
        this.status = Status.ACTIVE;
        this.effectiveFrom = effectiveFrom;
    }

    public static MedicineSchedule create(Member member, LocalTime alarmTime) {
        return create(member, alarmTime, LocalDate.now());
    }

    public static MedicineSchedule create(Member member, LocalTime alarmTime, LocalDate effectiveFrom) {
        return MedicineSchedule.builder()
                .member(member)
                .alarmTime(alarmTime)
                .effectiveFrom(effectiveFrom)
                .build();
    }

    public void addCategory(MedicineCategory category) {
        this.categories.add(category);
        category.setMedicineSchedule(this);
    }

    public void updateAlarmTime(LocalTime alarmTime) {
        this.alarmTime = alarmTime;
    }

    public void clearCategories() {
        this.categories.clear();
    }

    // 이 버전을 lastEffectiveDate까지만 유효하도록 마감한다 (수정·삭제 시 과거 보존용)
    public void closeAsOf(LocalDate lastEffectiveDate) {
        this.effectiveTo = lastEffectiveDate;
    }

    public boolean isEffectiveOn(LocalDate date) {
        if (date.isBefore(getEffectiveStartDate())) {
            return false;
        }
        if (effectiveTo == null) {
            return true;
        }
        return !date.isAfter(effectiveTo);
    }

    public boolean startedOn(LocalDate date) {
        return getEffectiveStartDate().equals(date);
    }

    // 현재 유효한 최신 버전인지 여부 (마감된 과거 버전은 수정·삭제 대상이 아니다)
    public boolean isCurrent() {
        return effectiveTo == null;
    }

    private LocalDate getEffectiveStartDate() {
        if (effectiveFrom != null) {
            return effectiveFrom;
        }
        if (getCreatedAt() != null) {
            return getCreatedAt().toLocalDate();
        }
        return LocalDate.MIN;
    }

    public Integer getTotalCount() {
        return categories.stream()
                .mapToInt(MedicineCategory::getCountSum)
                .sum();
    }
}
