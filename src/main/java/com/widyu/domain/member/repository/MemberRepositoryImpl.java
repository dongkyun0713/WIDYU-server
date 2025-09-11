package com.widyu.domain.member.repository;

import static com.widyu.domain.member.entity.QMember.member;
import static com.widyu.domain.member.entity.QSocialAccount.socialAccount;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.domain.member.entity.Member;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Optional<Member> findByProviderAndOauthId(String provider, String oauthId) {
        Member result = queryFactory
                .selectFrom(member)
                .join(member.socialAccounts, socialAccount).fetchJoin()
                .where(
                        socialAccount.provider.eq(provider),
                        socialAccount.oauthId.eq(oauthId)
                )
                .distinct()
                .fetchOne();

        return Optional.ofNullable(result);
    }
}
