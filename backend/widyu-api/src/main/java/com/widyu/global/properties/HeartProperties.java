package com.widyu.global.properties;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heart")
public record HeartProperties(
        Cleanup cleanup
) {
    public HeartProperties {
        cleanup = Objects.requireNonNullElseGet(cleanup, () -> new Cleanup(null));
    }

    public record Cleanup(
            List<Long> exemptMemberIds
    ) {
        public Cleanup {
            exemptMemberIds = Objects.requireNonNullElseGet(exemptMemberIds, List::of);
        }
    }
}
