package com.widyu.member.repository;

import com.widyu.member.domain.Member;
import java.util.Optional;

public interface MemberRepositoryCustom {
    Optional<Member> findByProviderAndOauthId(String provider, String oauthId);
}
