package com.widyu.run.repository;

import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RunDeviceAssignmentRepository extends JpaRepository<RunDeviceAssignment, Long> {

    Optional<RunDeviceAssignment> findByAssignmentId(String assignmentId);

    List<RunDeviceAssignment> findByRun_IdOrderByIdAsc(Long runDbId);

    List<RunDeviceAssignment> findByRun_IdAndUnassignedAtMsIsNull(Long runDbId);

    /** 기기는 한 번에 한 참가자에게만 간다. 열린 회차에 해제되지 않은 배정이 있으면 배정할 수 없다. */
    boolean existsByDeviceIdAndUnassignedAtMsIsNullAndRun_Status(
            String deviceId, CollectionRunStatus status);

    /**
     * 그 시점에 이 기기를 쓰고 있던 회원의 열린 회차. 배정 구간은 {@code [assigned, unassigned)}다.
     * 같은 회차에 배정·해제가 반복될 수 있어 목록으로 받는다.
     */
    @Query("""
            SELECT a.run FROM RunDeviceAssignment a
             WHERE a.run.member.id = :memberId
               AND a.run.status = com.widyu.run.CollectionRunStatus.OPEN
               AND a.deviceId = :deviceId
               AND a.assignedAtMs <= :atMs
               AND (a.unassignedAtMs IS NULL OR a.unassignedAtMs > :atMs)
             ORDER BY a.assignedAtMs DESC
            """)
    List<CollectionRun> findOpenRunsByDeviceAt(
            @Param("memberId") Long memberId,
            @Param("deviceId") String deviceId,
            @Param("atMs") long atMs);

    /** 명시한 회차가 해당 회원·기기의 그 시각 배정을 실제로 보유하는지 확인한다. */
    @Query("""
            SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END
              FROM RunDeviceAssignment a
             WHERE a.run.id = :runDbId
               AND a.run.member.id = :memberId
               AND a.deviceId = :deviceId
               AND a.assignedAtMs <= :atMs
               AND (a.unassignedAtMs IS NULL OR a.unassignedAtMs > :atMs)
            """)
    boolean existsAssignmentAt(
            @Param("runDbId") Long runDbId,
            @Param("memberId") Long memberId,
            @Param("deviceId") String deviceId,
            @Param("atMs") long atMs);
}
