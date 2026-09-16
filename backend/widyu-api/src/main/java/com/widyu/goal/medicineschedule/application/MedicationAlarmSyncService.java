package com.widyu.goal.medicineschedule.application;

import com.widyu.global.entity.Status;
import com.widyu.global.util.MemberUtil;
import com.widyu.goal.medicineschedule.dto.response.MedicationAlarmSyncResponse;
import com.widyu.goal.medicineschedule.repository.MedicationProofRepository;
import com.widyu.goal.medicineschedule.repository.MedicineScheduleRepository;
import com.widyu.member.Member;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MedicationAlarmSyncService {
    private static final String TIME_ZONE = "Asia/Seoul";
    private static final ZoneId TIME_ZONE_ID = ZoneId.of(TIME_ZONE);

    private final MemberUtil memberUtil;
    private final MedicineScheduleRepository medicineScheduleRepository;
    private final MedicationProofRepository medicationProofRepository;

    public MedicationAlarmSyncResponse getCurrentMemberSnapshot() {
        Member member = memberUtil.getCurrentMember();
        LocalDate today = LocalDate.now(TIME_ZONE_ID);
        List<Long> completedIds = medicationProofRepository.findVerifiedScheduleIdsByMemberAndDate(
                member.getId(), today.atStartOfDay(), today.atTime(LocalTime.MAX));
        List<String> completed = completedIds.stream().map(id -> id + ":" + today).toList();
        return MedicationAlarmSyncResponse.of(member.getMedicationAlarmRevision(), TIME_ZONE,
                medicineScheduleRepository.findEffectiveByMemberAndDateWithDetails(member, Status.ACTIVE, today), completed);
    }
}
