package com.widyu.followup.repository;

import com.widyu.followup.FollowupCard;
import com.widyu.followup.FollowupCardState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FollowupCardRepository extends JpaRepository<FollowupCard, Long> {
    boolean existsByIncidentRef(String incidentRef);
    Optional<FollowupCard> findBySeniorIdAndVisitKey(Long seniorId, String visitKey);
    Optional<FollowupCard> findFirstBySeniorIdAndStateAndVisitKeyIsNullAndExpiresAtMsGreaterThanOrderByIssuedAtMsAscIdAsc(
            Long seniorId, FollowupCardState state, long nowMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FollowupCard c SET c.visitKey = :visitKey WHERE c.id = :id "
            + "AND c.seniorId = :seniorId AND c.state = :issued "
            + "AND c.visitKey IS NULL AND c.expiresAtMs > :nowMs")
    int assignVisitIfIssued(@Param("id") Long id, @Param("seniorId") Long seniorId,
            @Param("visitKey") String visitKey, @Param("issued") FollowupCardState issued,
            @Param("nowMs") long nowMs);

    @Query("SELECT c.id FROM FollowupCard c WHERE c.id > :afterId AND c.state = :state "
            + "AND c.expiresAtMs <= :nowMs ORDER BY c.id")
    List<Long> findDueIds(@Param("state") FollowupCardState state, @Param("nowMs") long nowMs,
            @Param("afterId") long afterId, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FollowupCard c SET c.state = :expired WHERE c.id = :id "
            + "AND c.state = :issued AND c.expiresAtMs <= :nowMs")
    int expire(@Param("id") Long id, @Param("issued") FollowupCardState issued,
            @Param("expired") FollowupCardState expired, @Param("nowMs") long nowMs);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FollowupCard c SET c.state = :target WHERE c.id = :id AND c.seniorId = :seniorId "
            + "AND c.state IN (:issued, :expired) "
            + "AND (c.expiresAtMs > :receivedAtMs OR :deviceSubmittedAtMs < c.expiresAtMs)")
    int submit(@Param("id") Long id, @Param("seniorId") Long seniorId,
            @Param("issued") FollowupCardState issued, @Param("expired") FollowupCardState expired,
            @Param("target") FollowupCardState target, @Param("receivedAtMs") long receivedAtMs,
            @Param("deviceSubmittedAtMs") long deviceSubmittedAtMs);
}
