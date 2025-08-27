package com.widyu.auth.application.guardian;

import com.widyu.auth.domain.TemporaryMember;
import com.widyu.auth.dto.request.EmailCheckRequest;
import com.widyu.auth.dto.request.LocalGuardianSignInRequest;
import com.widyu.auth.dto.response.TokenPairResponse;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.member.domain.LocalAccount;
import com.widyu.member.domain.Member;
import com.widyu.member.domain.MemberType;
import com.widyu.member.repository.LocalAccountRepository;
import com.widyu.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LocalLoginService {

    private final PasswordEncoder passwordEncoder;
    private final MemberRepository memberRepository;
    private final LocalAccountRepository localAccountRepository;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public TokenPairResponse signupGuardianWithLocal(TemporaryMember temp, String email, String rawPassword) {
        if (localAccountRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.ALREADY_REGISTERED_EMAIL);
        }

        Member member = Member.createMember(
                MemberType.GUARDIAN,
                temp.getName(),
                temp.getPhoneNumber()
        );

        LocalAccount local = LocalAccount.createLocalAccount(
                member,
                email,
                passwordEncoder.encode(rawPassword)
        );

        memberRepository.save(member);
        localAccountRepository.save(local);

        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }

    @Transactional(readOnly = true)
    public boolean isEmailRegistered(EmailCheckRequest request) {
        return !localAccountRepository.existsByEmail(request.email());
    }

    @Transactional(readOnly = true)
    public TokenPairResponse signIn(LocalGuardianSignInRequest request) {
        LocalAccount localAccount = findLocalAccountByEmail(request.email());
        validatePassword(request.password(), localAccount.getPassword());

        Member member = localAccount.getMember();
        return jwtTokenProvider.generateTokenPair(member.getId(), member.getRole());
    }

    private LocalAccount findLocalAccountByEmail(String email) {
        return localAccountRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_EMAIL));
    }

    private void validatePassword(String rawPassword, String encodedPassword) {
        if (!passwordEncoder.matches(rawPassword, encodedPassword)) {
            throw new BusinessException(ErrorCode.INVALID_PASSWORD);
        }
    }
}
