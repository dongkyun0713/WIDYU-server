package com.widyu.authtest.application.guardian.oauth;

import com.widyu.authtest.domain.OAuthState;
import com.widyu.authtest.repository.OAuthStateTestRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.OAuthProperties;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthStateTestService {

    private final OAuthProperties oAuthProperties;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthStateTestRepository oAuthStateTestRepository;

    public String generateAndSaveState() {
        String state = generateRandomState();


        OAuthState oAuthState = OAuthState.builder()
                .state(state)
                .ttl(oAuthProperties.ttl())
                .build();
        oAuthStateTestRepository.save(oAuthState);
        return state;
    }

    public void validateAndConsumeState(String state) {
        if (state == null || state.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE, ": state is null or empty");
        }

        boolean exists = oAuthStateTestRepository.existsById(state);

        if (!exists) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE, ": state not found or already consumed");
        }

        oAuthStateTestRepository.deleteById(state);
        log.debug("OAuth state 검증 및 소모 완료: {}", state);
    }

    private String generateRandomState() {

        byte[] randomBytes = new byte[oAuthProperties.length()];
        SECURE_RANDOM.nextBytes(randomBytes);

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);
    }
}
