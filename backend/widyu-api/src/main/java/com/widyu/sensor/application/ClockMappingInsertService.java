package com.widyu.sensor.application;

import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.repository.ClockMappingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UK 경합이 현재 매핑 대조 트랜잭션을 rollback-only로 만들지 않게 INSERT를 분리한다. */
@Service
@RequiredArgsConstructor
public class ClockMappingInsertService {

    private final ClockMappingRepository clockMappingRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ClockMapping mapping) {
        clockMappingRepository.saveAndFlush(mapping);
    }
}
