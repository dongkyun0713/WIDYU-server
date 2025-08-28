package com.widyu.global.config;

import com.widyu.auth.application.guardian.oauth.OAuthClient;
import com.widyu.auth.domain.OAuthProvider;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OAuthConfig {

    private final Map<String, OAuthClient> oAuthClients;

    @Bean
    public Map<OAuthProvider, OAuthClient> oAuthClientMap() {
        return oAuthClients.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> OAuthProvider.from(entry.getKey()),
                        Map.Entry::getValue
                ));
    }
}
