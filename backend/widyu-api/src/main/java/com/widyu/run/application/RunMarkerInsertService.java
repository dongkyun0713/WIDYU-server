package com.widyu.run.application;

import com.widyu.run.RunMarker;
import com.widyu.run.repository.RunMarkerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** marker_id UK 충돌을 호출 트랜잭션과 분리해 멱등 재조회를 계속할 수 있게 한다. */
@Service
@RequiredArgsConstructor
public class RunMarkerInsertService {

    private final RunMarkerRepository runMarkerRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RunMarker insert(RunMarker marker) {
        return runMarkerRepository.saveAndFlush(marker);
    }
}
