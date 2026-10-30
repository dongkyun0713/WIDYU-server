package com.widyu.goal.addressbookmark.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.properties.JusoProperties;
import com.widyu.goal.addressbookmark.application.AddressSearchService;
import com.widyu.goal.addressbookmark.application.GeocodingService;
import feign.RetryableException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 주소 API 클라이언트가 OpenFeign 기본값(읽기 60초) 대신 설정한 타임아웃을 쓰는지
 * 실제 Feign 클라이언트와 느린 로컬 HTTP 서버로 확인한다(#856).
 * 서버는 1초 뒤에 응답하고 읽기 타임아웃은 300ms다.
 */
@SpringBootTest(
        classes = JusoApiClientTimeoutTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "juso.api.confm-key=test-juso-key",
                "juso.api.connect-timeout=1s",
                "juso.api.read-timeout=300ms"
        }
)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("주소 API 클라이언트 타임아웃 테스트")
class JusoApiClientTimeoutTest {

    private static final long SERVER_DELAY_MS = 1_000;
    private static final HttpServer SLOW_SERVER = startSlowServer();

    @Autowired private JusoApiClient jusoApiClient;
    @Autowired private AddressSearchService addressSearchService;
    @MockBean private GeocodingService geocodingService;

    @DynamicPropertySource
    static void jusoApiUrl(DynamicPropertyRegistry registry) {
        registry.add("juso.api.url", () -> "http://localhost:" + SLOW_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SLOW_SERVER.stop(0);
    }

    @Test
    @DisplayName("주소 API 응답이 읽기 타임아웃보다 늦으면 기다리지 않고 예외가 발생한다")
    void 주소_API_응답이_읽기_타임아웃보다_늦으면_예외가_발생한다() {
        // given
        long startedAt = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> jusoApiClient.searchAddress("test-juso-key", "성암로", 1, 10, "json"))
                .isInstanceOf(RetryableException.class);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).isLessThan(SERVER_DELAY_MS);
    }

    @Test
    @DisplayName("주소 검색이 타임아웃되면 승인키와 검색어를 로그에 남기지 않고 주소 검색 통신 오류가 발생한다")
    void 주소_검색이_타임아웃되면_승인키와_검색어_없이_통신_오류가_발생한다(CapturedOutput output) {
        // when & then
        assertThatThrownBy(() -> addressSearchService.search("성암로", 1, 10))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.JUSO_COMMUNICATION_ERROR));
        assertThat(output).contains("errorType=RetryableException");
        assertThat(output).doesNotContain("test-juso-key", "성암로");
    }

    private static HttpServer startSlowServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/addrLinkApi.do", exchange -> {
                try {
                    Thread.sleep(SERVER_DELAY_MS);
                    exchange.sendResponseHeaders(500, -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Configuration
    @ImportAutoConfiguration({
            FeignAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class
    })
    @EnableFeignClients(clients = JusoApiClient.class)
    @EnableConfigurationProperties(JusoProperties.class)
    @Import(AddressSearchService.class)
    static class TestApplication {
    }
}
