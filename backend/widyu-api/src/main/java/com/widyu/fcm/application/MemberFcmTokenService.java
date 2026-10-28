package com.widyu.fcm.application;

import com.widyu.fcm.MemberFcmToken;
import com.widyu.fcm.repository.MemberFcmTokenRepository;
import com.widyu.global.util.MemberUtil;
import com.widyu.member.Member;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberFcmTokenService {

    private final MemberFcmTokenRepository memberFcmTokenRepository;
    private final MemberUtil memberUtil;

    // 로그인 시 FCM 토큰 저장. 같은 토큰 등록이 동시에 와도 UK 충돌 없이 한 행으로 수렴하도록 한 문장으로 처리한다
    @Transactional
    public void saveOrActivateFcmToken(String fcmToken) {
        Member currentMember = memberUtil.getCurrentMember();
        memberFcmTokenRepository.upsertActive(fcmToken, currentMember.getId(), LocalDateTime.now());
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
