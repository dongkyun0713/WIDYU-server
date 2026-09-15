package com.widyu.fcm.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** firebase.messaging-url은 엄격한 URL 검증이 필요해 FcmMessagingUrl이 따로 읽는다. */
@ConfigurationProperties(prefix = "firebase")
public record FirebaseProperties(String configPath, @DefaultValue Http http) {

    public record Http(@DefaultValue("10s") Duration totalTimeout) {}
}
