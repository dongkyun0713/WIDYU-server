package com.widyu.global.filter;

import static com.widyu.global.constant.SecurityConstant.TOKEN_PREFIX;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.admin.validator.AdminAccessValidator;
import com.widyu.auth.dto.AccessTokenDto;
import com.widyu.global.error.BusinessException;
import com.widyu.global.security.JwtTokenProvider;
import com.widyu.global.security.PrincipalDetails;
import com.widyu.member.MemberRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Set<String> TEMPORARY_TOKEN_PATHS = Set.of(
            "/api/v1/auth/guardians/sign-up/local",
            "/api/v1/auth/guardians/password",
            "/api/v1/auth/guardians/apple/phone-number",
            "/api/v1/auth/guardians/profile/temporary",
            "/api/v1/auth/guardians/social/integration"
    );

    private final JwtTokenProvider jwtTokenProvider;
    private final AdminAccessValidator adminAccessValidator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return TEMPORARY_TOKEN_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String accessTokenHeaderValue = extractAccessTokenFromHeader(request);

        if (accessTokenHeaderValue != null) {
            try {
                AccessTokenDto accessTokenDto = jwtTokenProvider.retrieveAccessToken(accessTokenHeaderValue);
                if (accessTokenDto.memberRole() == MemberRole.ADMIN) {
                    adminAccessValidator.validateMemberId(accessTokenDto.memberId());
                }
                setAuthenticationToContext(accessTokenDto);
            } catch (BusinessException e) {
                SecurityContextHolder.clearContext();
                writeErrorResponse(response, e);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void writeErrorResponse(HttpServletResponse response, BusinessException e) throws IOException {
        response.setStatus(e.getErrorCode().getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "code", e.getErrorCode().getCode(),
                "message", e.getErrorCode().getMessage()
        ));
    }

    private void setAuthenticationToContext(final AccessTokenDto token) {
        UserDetails userDetails = new PrincipalDetails(token.memberId(), token.memberRole(), token.authVersion());
        Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String extractAccessTokenFromHeader(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION))
                .filter(header -> header.startsWith(TOKEN_PREFIX))
                .map(header -> header.replace(TOKEN_PREFIX, ""))
                .orElse(null);
    }
}
