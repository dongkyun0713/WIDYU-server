package com.widyu.walk;

import com.widyu.global.entity.BaseTimeEntity;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"member_id", "walk_date"})
)
public class Walk extends BaseTimeEntity {

    private static final int MAX_GOAL_STEPS = 10000;
    private static final int POINT_REWARD = 25;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "walk_date", nullable = false)
    private LocalDate walkDate;

    @Column(name = "goal_steps", nullable = false)
    private Integer goalSteps;

    @Column(name = "actual_steps", nullable = false)
    private Integer actualSteps = 0;

    @Column(name = "rewarded", nullable = false)
    private boolean rewarded = false;

    @Builder(access = AccessLevel.PRIVATE)
    private Walk(Member member, LocalDate walkDate, Integer goalSteps, Integer actualSteps) {
        this.member = member;
        this.walkDate = walkDate;
        this.goalSteps = goalSteps;
        this.actualSteps = actualSteps != null ? actualSteps : 0;
    }

    public static Walk createWithGoal(Member member, LocalDate date, Integer goalSteps) {
        validateGoalSteps(goalSteps);
        return Walk.builder()
                .member(member)
                .walkDate(date)
                .goalSteps(goalSteps)
                .actualSteps(0)
                .build();
    }

    public void updateGoal(Integer newGoalSteps) {
        validateGoalSteps(newGoalSteps);
        this.goalSteps = newGoalSteps;
    }

    public void updateActualSteps(Integer steps) {
        if (steps == null || steps < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "걸음 수는 0 이상이어야 합니다.");
        }
        this.actualSteps = steps;
    }

    public boolean isGoalAchieved() {
        return this.actualSteps >= this.goalSteps;
    }

    public void markRewarded() {
        this.rewarded = true;
    }

    public Integer getPointRewarded() {
        return isGoalAchieved() ? POINT_REWARD : 0;
    }

    private static void validateGoalSteps(Integer goalSteps) {
        if (goalSteps == null || goalSteps <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "목표 걸음 수는 1 이상이어야 합니다.");
        }
        if (goalSteps > MAX_GOAL_STEPS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    String.format("목표 걸음 수는 최대 %d보까지 설정 가능합니다.", MAX_GOAL_STEPS));
        }
    }
}
