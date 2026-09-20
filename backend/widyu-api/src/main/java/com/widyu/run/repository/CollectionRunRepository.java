package com.widyu.run.repository;

import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface CollectionRunRepository extends JpaRepository<CollectionRun, Long> {

    Optional<CollectionRun> findByRunId(String runId);

    /** 같은 회차의 export 생성 요청을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT run FROM CollectionRun run WHERE run.runId = :runId")
    Optional<CollectionRun> findByRunIdForUpdate(String runId);

    /** B12(서버 결정 수집 모드)가 「이 회원이 연구 중인가」를 물을 때 쓴다. */
    boolean existsByMemberIdAndStatus(Long memberId, CollectionRunStatus status);
}
