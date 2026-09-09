package com.widyu.fcm.application;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FcmMessagingUrl {

    private static final String FCM_HOST = "fcm.googleapis.com";
    private static final String DEFAULT_URL = "https://fcm.googleapis.com/v1/projects/widyu-d384f/messages:send";

    private final URI value;

    public FcmMessagingUrl(@Value("${firebase.messaging-url:" + DEFAULT_URL + "}") String rawUrl) {
        this.value = parse(rawUrl);
    }

    public URI value() {
        return value;
    }

    static URI parse(String rawUrl) {
        try {
            URI url = URI.create(rawUrl);
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme())
                    || host == null
                    || !FCM_HOST.equals(host.toLowerCase(Locale.ROOT))
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || url.getUserInfo() != null
                    || url.getFragment() != null) {
                throw new IllegalArgumentException("firebase.messaging-url은 HTTPS FCM URL이어야 합니다.");
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("firebase.messaging-url은 HTTPS FCM URL이어야 합니다.", exception);
        }
    }
}
