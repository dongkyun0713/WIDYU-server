package com.widyu.run.application;

import com.widyu.run.CollectionRunStatus;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UK 충돌 뒤 롤백된 명령 트랜잭션과 분리된 스냅샷에서 승자 행을 확인한다. */
@Service
@RequiredArgsConstructor
public class CollectionRunConflictLookupService {

    private final CollectionRunRepository collectionRunRepository;
    private final RunDeviceAssignmentRepository runDeviceAssignmentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasOpenRun(Long memberId) {
        return collectionRunRepository.existsByMemberIdAndStatus(memberId, CollectionRunStatus.OPEN);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasActiveDevice(String deviceId) {
        return runDeviceAssignmentRepository.existsByDeviceIdAndUnassignedAtMsIsNullAndRun_Status(
                deviceId, CollectionRunStatus.OPEN);
    }
}
