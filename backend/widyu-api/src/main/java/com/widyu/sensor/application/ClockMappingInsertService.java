package com.widyu.sensor.application;

import com.widyu.sensor.ClockMapping;
import com.widyu.sensor.repository.ClockMappingRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** UK 경합이 현재 매핑 대조 트랜잭션을 rollback-only로 만들지 않게 INSERT와 경합 뒤 재조회를 분리한다. */
@Service
@RequiredArgsConstructor
public class ClockMappingInsertService {

    private final ClockMappingRepository clockMappingRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ClockMapping mapping) {
        clockMappingRepository.saveAndFlush(mapping);
    }

    /**
     * UK 경합 뒤 승자 행을 새 스냅샷에서 읽는다. 호출 트랜잭션은 MySQL REPEATABLE READ에서 첫 조회 때 고정한
     * 스냅샷을 유지하므로, 같은 트랜잭션에서 다시 읽으면 다른 요청이 커밋한 행이 보이지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ClockMapping> findCommitted(String clockMappingId) {
        return clockMappingRepository.findByClockMappingId(clockMappingId);
    }
}
