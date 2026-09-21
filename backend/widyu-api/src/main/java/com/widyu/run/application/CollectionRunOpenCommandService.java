package com.widyu.run.application;

import com.widyu.run.CollectionRun;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.study.StudyParticipation;
import com.widyu.study.application.StudyParticipationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회차 생성과 최초 기기 배정을 하나의 독립 트랜잭션으로 저장한다.
 *
 * <p>연구 회차는 이 트랜잭션 안에서 참여 기록을 잠그고 다시 검사한 뒤 FK를 단다.
 * 바깥에서 한 번 검사하고 여기서 저장하면, 그 사이에 철회된 참여로 회차가 열린다.
 */
@Service
@RequiredArgsConstructor
public class CollectionRunOpenCommandService {

    private final CollectionRunRepository collectionRunRepository;
    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    private final StudyParticipationService studyParticipationService;

    /** {@code participationId}가 있으면 연구 회차다. 없으면 {@code product} 회차로 그대로 저장한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectionRun open(
            CollectionRun run, List<RunDeviceAssignment> assignments, String participationId) {
        if (participationId != null) {
            StudyParticipation participation =
                    studyParticipationService.requireActiveForRun(participationId, run.getMember().getId());
            run.attachParticipation(participation);
        }
        CollectionRun savedRun = collectionRunRepository.saveAndFlush(run);
        for (RunDeviceAssignment assignment : assignments) {
            runDeviceAssignmentRepository.save(assignment);
        }
        runDeviceAssignmentRepository.flush();
        return savedRun;
    }
}
