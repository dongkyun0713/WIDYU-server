package com.widyu.auth.application.guardian;

import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategy;
import com.widyu.auth.application.guardian.oauth.strategy.SocialLoginStrategyFactory;
import com.widyu.auth.domain.OAuthProvider;
import com.widyu.auth.dto.request.MemberWithdrawRequest;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.SocialAccount;
import com.widyu.member.repository.MemberRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawService {

    private final MemberRepository memberRepository;
    private final SocialLoginStrategyFactory strategyFactory;
    private final MemberUtil memberUtil;

    @Transactional
    public void withdrawMember(MemberWithdrawRequest request) {
        Member member = memberUtil.getCurrentMember();
        
        log.info("회원 탈퇴 시작: memberId={}, reason={}", member.getId(), request.reason());

        // 1. 연동된 모든 소셜 계정 탈퇴
        withdrawAllSocialAccounts(member, request.socialAccessTokens());

        // 2. 개인정보 마스킹 (GDPR 준수)
        member.maskPersonalInfo();

        // 3. 로컬 계정도 비활성화 (완전 삭제 대신 상태 변경)
        member.withdraw();

        // 4. 회원 데이터 저장
        memberRepository.save(member);
        
        log.info("회원 탈퇴 완료: memberId={}", member.getId());
    }

    private void withdrawAllSocialAccounts(Member member, Map<String, String> socialAccessTokens) {
        for (SocialAccount socialAccount : member.getSocialAccounts()) {
            String provider = socialAccount.getProvider();
            String accessToken = socialAccessTokens != null ? socialAccessTokens.get(provider) : null;
            
            // 카카오의 경우 어드민 키로 탈퇴하므로 액세스 토큰 불필요
            if ("kakao".equals(provider)) {
                try {
                    withdrawSocialAccount(provider, null, socialAccount.getOauthId());
                } catch (Exception e) {
                    log.warn("카카오 계정 탈퇴 실패하지만 진행 계속: oauthId={}, error={}", 
                            socialAccount.getOauthId(), e.getMessage());
                }
            } 
            // 애플, 네이버의 경우 저장된 리프레시 토큰 사용
            else if (("apple".equals(provider) || "naver".equals(provider)) 
                    && socialAccount.getRefreshToken() != null && !socialAccount.getRefreshToken().isBlank()) {
                try {
                    withdrawSocialAccount(provider, socialAccount.getRefreshToken(), socialAccount.getOauthId());
                } catch (Exception e) {
                    log.warn("{} 계정 탈퇴 실패하지만 진행 계속: oauthId={}, error={}", 
                            provider, socialAccount.getOauthId(), e.getMessage());
                }
            }
            // 다른 제공자들은 액세스 토큰 필요
            else if (accessToken != null && !accessToken.isBlank()) {
                try {
                    withdrawSocialAccount(provider, accessToken, socialAccount.getOauthId());
                } catch (Exception e) {
                    log.warn("소셜 계정 탈퇴 실패하지만 진행 계속: provider={}, oauthId={}, error={}", 
                            provider, socialAccount.getOauthId(), e.getMessage());
                }
            } else {
                log.warn("소셜 계정 탈퇴를 위한 액세스 토큰 없음: provider={}, oauthId={}", 
                        provider, socialAccount.getOauthId());
            }
        }
    }

    private void withdrawSocialAccount(String providerName, String accessToken, String oauthId) {
        try {
            OAuthProvider provider = OAuthProvider.from(providerName);
            SocialLoginStrategy strategy = strategyFactory.getStrategy(providerName);
            
            strategy.withdrawSocialAccount(accessToken, oauthId);
            
            log.info("소셜 계정 탈퇴 성공: provider={}, oauthId={}", providerName, oauthId);
        } catch (Exception e) {
            log.error("소셜 계정 탈퇴 실패: provider={}, oauthId={}, error={}", 
                    providerName, oauthId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}