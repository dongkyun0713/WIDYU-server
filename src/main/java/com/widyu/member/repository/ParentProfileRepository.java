package com.widyu.member.repository;

import com.widyu.member.domain.ParentProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentProfileRepository extends JpaRepository<ParentProfile, Long> {
    Optional<ParentProfile> findByInviteCode(String inviteCode);
    List<ParentProfile> findAllByInviteCodeIn(Collection<String> inviteCodes);
}
