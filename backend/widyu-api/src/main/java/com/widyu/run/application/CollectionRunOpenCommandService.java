package com.widyu.run.application;

import com.widyu.run.CollectionRun;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 회차 생성과 최초 기기 배정을 하나의 독립 트랜잭션으로 저장한다. */
@Service
@RequiredArgsConstructor
public class CollectionRunOpenCommandService {

    private final CollectionRunRepository collectionRunRepository;
    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectionRun open(CollectionRun run, List<RunDeviceAssignment> assignments) {
        CollectionRun savedRun = collectionRunRepository.saveAndFlush(run);
        for (RunDeviceAssignment assignment : assignments) {
            runDeviceAssignmentRepository.save(assignment);
        }
        runDeviceAssignmentRepository.flush();
        return savedRun;
    }
}
