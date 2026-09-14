package com.widyu.global.properties;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth.proxy")
public record AuthProxyProperties(List<String> trustedCidrs) {
    public AuthProxyProperties {
        if (trustedCidrs == null) {
            trustedCidrs = List.of();
        }
        trustedCidrs = List.copyOf(trustedCidrs);
    }
}
