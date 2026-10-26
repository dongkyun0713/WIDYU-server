package com.widyu.goal.healthschedule;

import com.widyu.healthschedule.HealthSchedule;
import com.widyu.healthschedule.ProgressStatus;
import com.widyu.member.Member;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

public final class HealthScheduleFixture {

    private HealthScheduleFixture() {
    }

    public static HealthSchedule schedule(Member member, Long id, LocalDateTime scheduledAt, ProgressStatus progressStatus) {
        HealthSchedule schedule = HealthSchedule.create(
                member, "건강 일정 " + id, "서울특별시 성북구 고려대로 73", 37.5894, 127.0327, scheduledAt);
        ReflectionTestUtils.setField(schedule, "id", id);
        ReflectionTestUtils.setField(schedule, "progressStatus", progressStatus);
        return schedule;
    }
}
