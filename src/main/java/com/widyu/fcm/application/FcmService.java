package com.widyu.fcm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.widyu.fcm.api.dto.FcmMessageDto;
import com.widyu.fcm.api.dto.FcmSendDto;
import com.widyu.fcm.api.dto.response.FcmNotificationResponses;
import com.widyu.fcm.api.dto.response.FcmSendResponse;
import com.widyu.fcm.domain.FcmNotification;
import com.widyu.fcm.domain.MemberFcmToken;
import com.widyu.fcm.domain.repository.FcmNotificationRepository;
import com.widyu.fcm.domain.repository.MemberFcmTokenRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.domain.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FcmService {

    @Value("${firebase.config-path}")
    private String firebaseConfigPath;

    private final FcmNotificationRepository fcmNotificationRepository;
    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final MemberUtil memberUtil;
    private static final String API_URL = "https://fcm.googleapis.com/v1/projects/widuy-875a5/messages:send";

    @Transactional
    public FcmSendResponse sendMessageTo(FcmSendDto fcmSendDto) throws IOException {
        Member member = memberUtil.getCurrentMember();

        List<MemberFcmToken> tokens = memberFcmTokenRepository.findAllByMemberIdAndActiveTrue(member.getId());

        int successCount = 0;

        for (MemberFcmToken tokenEntity : tokens) {
            String token = tokenEntity.getToken();
            String message = makeMessage(token, fcmSendDto);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(getAccessToken());

            HttpEntity<String> entity = new HttpEntity<>(message, headers);
            RestTemplate restTemplate = new RestTemplate();
            restTemplate.getMessageConverters()
                    .add(0, new org.springframework.http.converter.StringHttpMessageConverter(StandardCharsets.UTF_8));

            ResponseEntity<String> response = restTemplate.exchange(API_URL, HttpMethod.POST, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                successCount++;

                fcmNotificationRepository.save(FcmNotification.builder()
                        .title(fcmSendDto.title())
                        .body(fcmSendDto.body())
                        .memberFcmToken(tokenEntity)
                        .isRead(false)
                        .build());
            }
        }

        return FcmSendResponse.of(fcmSendDto, successCount);
    }

    private String makeMessage(String token, FcmSendDto dto) throws JsonProcessingException {
        ObjectMapper om = new ObjectMapper();

        FcmMessageDto fcmMessageDto = FcmMessageDto.builder()
                .message(FcmMessageDto.Message.builder()
                        .token(token)
                        .notification(FcmMessageDto.Notification.builder()
                                .title(dto.title())
                                .body(dto.body())
                                .image(null)
                                .build())
                        .build())
                .validateOnly(false)
                .build();

        return om.writeValueAsString(fcmMessageDto);
    }

    private String getAccessToken() throws IOException {
        GoogleCredentials googleCredentials = GoogleCredentials
                .fromStream(new ClassPathResource(firebaseConfigPath).getInputStream())
                .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));

        googleCredentials.refreshIfExpired();

        return googleCredentials.getAccessToken().getTokenValue();
    }

    // 유저별 알림 목록 조회 api 구현
    public FcmNotificationResponses getNotificationsForCurrentUser() {
        Member member = memberUtil.getCurrentMember();

        return FcmNotificationResponses.from(
                fcmNotificationRepository.findAllByMemberFcmToken_MemberIdOrderByCreatedAtDesc(member.getId())
        );
    }

    // 알림 전체 읽기
    @Transactional
    public void markAllAsRead() {
        Member member = memberUtil.getCurrentMember();
        fcmNotificationRepository.markAllAsReadByMemberId(member.getId());
    }

    // 알림 개별 읽기
    @Transactional
    public void markAsRead(Long notificationId) {
        Member member = memberUtil.getCurrentMember();
        FcmNotification notification = fcmNotificationRepository
                .findByIdAndMemberFcmToken_MemberId(notificationId, member.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FCM_NOTIFICATION_NOT_FOUND));

        if (!notification.isRead()) {
            notification.markAsRead();
        }
    }

}
