package com.widyu.fcm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.widyu.fcm.api.dto.FcmMessageDto;
import com.widyu.fcm.api.dto.FcmSendDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
public class FcmServiceImpl implements FcmService {

    @Value("${firebase.config-path}")
    private String firebaseConfigPath;

    @Override
    public int sendMessageTo(FcmSendDto fcmSendDto) throws IOException {
        String message = makeMessage(fcmSendDto);

        log.info("[FCM Request] {}", message);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getMessageConverters()
                .add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + getAccessToken());

        HttpEntity<String> entity = new HttpEntity<>(message, headers);

        String API_URL = "https://fcm.googleapis.com/v1/projects/widuy-875a5/messages:send";
        ResponseEntity<String> response =
                restTemplate.exchange(API_URL, HttpMethod.POST, entity, String.class);

        log.info("[FCM Response] status: {}, body: {}", response.getStatusCode(), response.getBody());

        return response.getStatusCode() == HttpStatus.OK ? 1 : 0;
    }

    private String getAccessToken() throws IOException {
        GoogleCredentials googleCredentials = GoogleCredentials
                .fromStream(new ClassPathResource(firebaseConfigPath).getInputStream())
                .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));

        googleCredentials.refreshIfExpired();
        return googleCredentials.getAccessToken().getTokenValue();
    }

    private String makeMessage(FcmSendDto fcmSendDto) throws JsonProcessingException {
        ObjectMapper om = new ObjectMapper();

        FcmMessageDto fcmMessageDto = FcmMessageDto.builder()
                .message(FcmMessageDto.Message.builder()
                        .token(fcmSendDto.token())
                        .notification(FcmMessageDto.Notification.builder()
                                .title(fcmSendDto.title())
                                .body(fcmSendDto.body())
                                .image(null)
                                .build()
                        ).build())
                .validateOnly(false)
                .build();

        return om.writeValueAsString(fcmMessageDto);
    }
}
