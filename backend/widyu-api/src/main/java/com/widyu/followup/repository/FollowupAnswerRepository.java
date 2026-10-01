package com.widyu.followup.repository;

import com.widyu.followup.FollowupAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowupAnswerRepository extends JpaRepository<FollowupAnswer, Long> {
    boolean existsByCardId(Long cardId);
}
