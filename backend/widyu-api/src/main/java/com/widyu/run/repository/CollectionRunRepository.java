package com.widyu.run.repository;

import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    Optional<CollectionRun> findByRunId(String runId);

    /** B12(서버 결정 수집 모드)가 「이 회원이 연구 중인가」를 물을 때 쓴다. */
    boolean existsByMemberIdAndStatus(Long memberId, CollectionRunStatus status);
}
