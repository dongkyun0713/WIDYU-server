package com.widyu.authtest.config;

import com.widyu.authtest.application.guardian.oauth.OAuthTestClient;
import com.widyu.authtest.domain.OAuthProvider;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class OAuthTestConfig {

    private final Map<String, OAuthTestClient> oAuthClients;

    @Bean("oAuthClientMapTest")
    public Map<OAuthProvider, OAuthTestClient> oAuthClientMap() {
        return oAuthClients.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> OAuthProvider.from(entry.getKey()),
                        Map.Entry::getValue
                ));
    }
}
