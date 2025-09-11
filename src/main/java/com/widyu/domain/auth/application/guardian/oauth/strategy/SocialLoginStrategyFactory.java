package com.widyu.domain.auth.application.guardian.oauth.strategy;

import com.widyu.domain.auth.entity.OAuthProvider;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SocialLoginStrategyFactory {

    private final Map<OAuthProvider, SocialLoginStrategy> strategies;

    public SocialLoginStrategyFactory(List<SocialLoginStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                    SocialLoginStrategy::getSupportedProvider,
                    Function.identity()
                ));
        
        log.info("소셜 로그인 전략 팩토리 초기화 완료 - 전략 개수: {}, 지원 제공자: {}", 
                strategies.size(), strategies.keySet());
    }

    public SocialLoginStrategy getStrategy(OAuthProvider provider) {
        SocialLoginStrategy strategy = strategies.get(provider);
        if (strategy == null) {
            log.error("지원하지 않는 OAuth 제공자입니다: {}", provider);
            throw new BusinessException(ErrorCode.UNSUPPORTED_OAUTH_PROVIDER, provider.name());
        }
        return strategy;
    }

    public SocialLoginStrategy getStrategy(String providerName) {
        OAuthProvider provider = OAuthProvider.from(providerName);
        return getStrategy(provider);
    }
}
