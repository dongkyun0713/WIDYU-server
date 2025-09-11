package com.widyu.domain.member.repository;

import com.widyu.domain.member.entity.ParentProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentProfileRepository extends JpaRepository<ParentProfile, Long> {
    Optional<ParentProfile> findByInviteCodeAndMemberPhoneNumber(String inviteCode, String phoneNumber);
    List<ParentProfile> findAllByInviteCodeIn(Collection<String> inviteCodes);
}
