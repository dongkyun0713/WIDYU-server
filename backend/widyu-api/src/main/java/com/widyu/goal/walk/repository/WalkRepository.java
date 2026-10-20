package com.widyu.goal.walk.repository;

import com.widyu.member.Member;
import com.widyu.walk.Walk;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalkRepository extends JpaRepository<Walk, Long> {

    Optional<Walk> findByMemberAndWalkDate(Member member, LocalDate walkDate);

    List<Walk> findByMemberAndWalkDateBetweenOrderByWalkDateAsc(
            Member member,
            LocalDate startDate,
            LocalDate endDate
    );

    @Query("SELECT COUNT(w) FROM Walk w " +
           "WHERE w.member.id = :memberId " +
           "AND w.walkDate BETWEEN :startDate AND :endDate " +
           "AND w.actualSteps >= w.goalSteps")
    long countAchievedGoals(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Query("SELECT COUNT(w) FROM Walk w " +
           "WHERE w.member.id = :memberId " +
           "AND w.walkDate BETWEEN :startDate AND :endDate")
    long countTotalRecords(
            @Param("memberId") Long memberId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    boolean existsByMemberAndWalkDate(Member member, LocalDate walkDate);

    Optional<Walk> findFirstByMemberOrderByWalkDateAsc(Member member);

    Optional<Walk> findFirstByMemberAndWalkDateAfterOrderByWalkDateAsc(Member member, LocalDate walkDate);

    @Query("SELECT w FROM Walk w " +
           "WHERE w.walkDate = :date " +
           "AND w.actualSteps < w.goalSteps")
    List<Walk> findUnachievedWalksByDate(@Param("date") LocalDate date);
}
