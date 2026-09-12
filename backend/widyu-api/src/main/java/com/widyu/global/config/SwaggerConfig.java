package com.widyu.global.config;

import com.widyu.global.properties.SwaggerProperties;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class SwaggerConfig {

    // swagger 정보
    private static final String SWAGGER_TITLE = "Widyu API";
    private static final String SWAGGER_DESCRIPTION = "Widyu API Documentation";

    // 보안 정보
    private static final String BEARER_SCHEME_NAME = "BearerAuth";
    private static final String BEARER_TYPE = "bearer";
    private static final String BEARER_FORMAT = "JWT";

    // 서버 정보
    private static final String LOCAL_IDENTIFIER = "localhost";
    private static final String LOCAL_SERVER = "Local Server";
    private static final String DEV_SERVER = "Dev Server";

    private final SwaggerProperties swaggerProperties;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(List.of(server()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME_NAME))
                .components(new Components().addSecuritySchemes(
                                BEARER_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(BEARER_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme(BEARER_TYPE)
                                        .bearerFormat(BEARER_FORMAT)
                        )
                );
    }

    private Info apiInfo() {
        return new Info()
                .title(SWAGGER_TITLE)
                .description(SWAGGER_DESCRIPTION)
                .version(swaggerProperties.version());
    }

    private Server server() {
        String url = swaggerProperties.url();
        String description = DEV_SERVER;
        if (url.contains(LOCAL_IDENTIFIER)) {
            description = LOCAL_SERVER;
        }
        return new Server().url(url).description(description);
    }

    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("전체 API")
                .pathsToMatch("/**")
                .build();
    }

    @Bean
    public GroupedOpenApi authApi() {
        return GroupedOpenApi.builder()
                .group("인증 API")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/sms/**")
                .build();
    }

    @Bean
    public GroupedOpenApi albumApi() {
        return GroupedOpenApi.builder()
                .group("앨범 API")
                .pathsToMatch("/api/v1/albums/**", "/api/v1/album/**")
                .build();
    }

    @Bean
    public GroupedOpenApi goalApi() {
        return GroupedOpenApi.builder()
                .group("목표 API")
                .pathsToMatch("/api/v1/goals/**")
                .build();
    }

    @Bean
    public GroupedOpenApi memberApi() {
        return GroupedOpenApi.builder()
                .group("회원 API")
                .pathsToMatch("/api/v1/members/**")
                .build();
    }

    @Bean
    public GroupedOpenApi paymentApi() {
        return GroupedOpenApi.builder()
                .group("결제 API")
                .pathsToMatch("/api/v1/payment/**")
                .build();
    }

    @Bean
    public GroupedOpenApi notificationApi() {
        return GroupedOpenApi.builder()
                .group("알림 API")
                .pathsToMatch("/api/v1/fcm/**")
                .build();
    }

    @Bean
    public GroupedOpenApi locationApi() {
        return GroupedOpenApi.builder()
                .group("위치 API")
                .pathsToMatch("/api/v1/location/**")
                .build();
    }

    @Bean
    public GroupedOpenApi heartRateApi() {
        return GroupedOpenApi.builder()
                .group("심박수 API")
                .pathsToMatch("/api/v1/heart-rate/**")
                .build();
    }

    @Bean
    public GroupedOpenApi myPageApi() {
        return GroupedOpenApi.builder()
                .group("마이페이지 API")
                .pathsToMatch("/api/v1/mypage/**")
                .build();
    }

    @Bean
    public GroupedOpenApi homeApi() {
        return GroupedOpenApi.builder()
                .group("홈 API")
                .pathsToMatch("/api/v1/home/**")
                .build();
    }
}
