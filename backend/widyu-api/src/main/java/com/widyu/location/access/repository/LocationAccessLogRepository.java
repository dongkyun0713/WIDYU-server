package com.widyu.location.access.repository;

import com.widyu.location.access.LocationAccessLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 추가 전용 테이블이라 조회와 {@code notified_at} 갱신만 정의한다(LLD-0056 4절).
 */
@Repository
public interface LocationAccessLogRepository extends JpaRepository<LocationAccessLog, Long> {

    Page<LocationAccessLog> findBySeniorMemberIdAndAccessedAtBetween(
            Long seniorMemberId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    /**
     * 쿨다운 안에 같은 보호자에게 이미 통보했는지 본다(LLD-0056 5.3).
     * 방금 넣은 행은 {@code notified_at}이 비어 있어 자기 자신에 걸리지 않는다.
     */
    boolean existsBySeniorMemberIdAndViewerMemberIdAndNotifiedAtAfter(
            Long seniorMemberId, Long viewerMemberId, LocalDateTime notifiedAfter);

    /** 다이제스트 대상. 즉시 모드에서 쿨다운으로 합쳐진 행도 여기 들어온다(LLD-0056 5.4). */
    List<LocationAccessLog> findByNotifiedAtIsNullOrderBySeniorMemberIdAscAccessedAtAsc();

    @Modifying(clearAutomatically = true)
    @Query("update LocationAccessLog l set l.notifiedAt = :notifiedAt where l.id in :ids")
    int markNotified(@Param("ids") List<Long> ids, @Param("notifiedAt") LocalDateTime notifiedAt);
}
