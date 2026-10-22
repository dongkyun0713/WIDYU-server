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
     * UK 경합 뒤 상대가 커밋한 행을 읽는다(LLD-0044 5.1 2단계, LLD-0103). 호출 트랜잭션에서 다시 읽으면 첫 조회로
     * 고정된 REPEATABLE READ 스냅샷이라 그 행이 보이지 않는다. 새 트랜잭션·새 persistence context에서 읽는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ClockMapping> findCommitted(String clockMappingId) {
        return clockMappingRepository.findByClockMappingId(clockMappingId);
    }
}
