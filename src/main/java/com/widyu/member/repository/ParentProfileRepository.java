package com.widyu.member.repository;

import com.widyu.member.domain.ParentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentProfileRepository extends JpaRepository<ParentProfile, Long> {
    boolean existsByInviteCode(String inviteCode);
}
