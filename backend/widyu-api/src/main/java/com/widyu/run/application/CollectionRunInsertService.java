package com.widyu.run.application;

import com.widyu.run.CollectionRun;
import com.widyu.run.repository.CollectionRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UK 충돌이 바깥 업무 트랜잭션을 rollback-only로 만들지 않도록 삽입을 분리한다. */
@Service
@RequiredArgsConstructor
public class CollectionRunInsertService {

    private final CollectionRunRepository collectionRunRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CollectionRun insert(CollectionRun run) {
        return collectionRunRepository.saveAndFlush(run);
    }
}
