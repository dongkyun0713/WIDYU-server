package com.widyu.domain.fcm.application;

import com.widyu.domain.fcm.domain.MemberFcmToken;
import com.widyu.domain.fcm.domain.repository.MemberFcmTokenRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.domain.member.domain.Member;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberFcmTokenService {

    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final MemberUtil memberUtil;

    // 로그인 시 FCM 토큰 저장 (중복 저장 방지)
    @Transactional
    public void saveOrActivateFcmToken(String fcmToken, String deviceInfo) {
        Member currentMember = memberUtil.getCurrentMember();

        Optional<MemberFcmToken> tokenOpt = memberFcmTokenRepository.findByToken(fcmToken);

        if (tokenOpt.isPresent()) {
            MemberFcmToken existingToken = tokenOpt.get();

            if (!existingToken.getMember().equals(currentMember)) {
                existingToken.deactivate();
            } else {
                // 같은 유저가 다시 로그인하면 토큰 활성화
                existingToken.activate();
            }

        } else {
            MemberFcmToken newToken = MemberFcmToken.builder()
                    .member(currentMember)
                    .token(fcmToken)
                    .deviceInfo(deviceInfo)
                    .registeredAt(LocalDateTime.now())
                    .active(true)
                    .build();
            memberFcmTokenRepository.save(newToken);
        }
    }

    // 로그아웃 시 FCM 토큰 삭제
    @Transactional
    public void deactivateFcmToken(String fcmToken) {
        memberFcmTokenRepository.findByToken(fcmToken)
                .ifPresent(MemberFcmToken::deactivate);
    }

    @Transactional
    public void deactivateInactiveTokens() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(60);
        List<MemberFcmToken> tokens = memberFcmTokenRepository.findAllByLastUsedAtBeforeAndActiveTrue(threshold);

        for (MemberFcmToken token : tokens) {
            token.deactivate();
        }
    }

}
