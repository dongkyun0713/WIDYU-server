package com.widyu.run.application;

import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 기기 이중 배정 UK 충돌을 호출 트랜잭션과 격리한다. */
@Service
@RequiredArgsConstructor
public class RunDeviceAssignmentInsertService {

    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunDeviceAssignment insert(RunDeviceAssignment assignment) {
        return runDeviceAssignmentRepository.saveAndFlush(assignment);
    }
}
