package com.widyu.auth.application.guardian.oauth;

import com.widyu.auth.domain.OAuthState;
import com.widyu.auth.repository.OAuthStateRepository;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.OAuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuthStateService {

    private final OAuthProperties oAuthProperties;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OAuthStateRepository oAuthStateRepository;

    public String generateAndSaveState() {
        String state = generateRandomState();


        OAuthState oAuthState = OAuthState.builder()
                .state(state)
                .ttl(oAuthProperties.ttl())
                .build();
        oAuthStateRepository.save(oAuthState);
        return state;
    }

    public void validateAndConsumeState(String state) {
        if (state == null || state.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE, ": state is null or empty");
        }

        boolean exists = oAuthStateRepository.existsById(state);

        if (!exists) {
            throw new BusinessException(ErrorCode.INVALID_OAUTH_STATE, ": state not found or already consumed");
        }

        oAuthStateRepository.deleteById(state);
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
