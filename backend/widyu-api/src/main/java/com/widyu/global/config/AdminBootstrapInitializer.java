package com.widyu.global.config;

import com.widyu.global.util.PiiMaskingUtil;
import com.widyu.member.LocalAccount;
import com.widyu.member.Member;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Order(1)
@Profile("!test")
@RequiredArgsConstructor
public class AdminBootstrapInitializer implements CommandLineRunner {

    private final MemberRepository memberRepository;
    private final LocalAccountRepository localAccountRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (localAccountRepository.existsByEmail(adminEmail)) {
            return;
        }
        Member admin = memberRepository.save(Member.createAdminMember("관리자", "01000000000"));
        localAccountRepository.save(
                LocalAccount.createLocalAccount(admin, adminEmail, passwordEncoder.encode(adminPassword))
        );
        log.info("[AdminBootstrap] 관리자 계정 생성 완료: {}", PiiMaskingUtil.maskEmail(adminEmail));
    }
}
