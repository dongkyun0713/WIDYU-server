package com.widyu.domain.auth.application;

import com.widyu.domain.auth.repository.RefreshTokenRepository;
import com.widyu.global.util.MemberUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final MemberUtil memberUtil;

    @Transactional
    public void logout() {
        Long memberId = memberUtil.getCurrentMember().getId();
        // 멱등: 존재하지 않아도 예외 없이 무시
        refreshTokenRepository.deleteById(memberId);
    }
}

