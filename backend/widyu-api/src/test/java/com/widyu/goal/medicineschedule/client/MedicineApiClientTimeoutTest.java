package com.widyu.goal.medicineschedule.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.sun.net.httpserver.HttpServer;
import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.application.MedicineUpsertService;
import com.widyu.goal.medicineschedule.scheduler.MedicineSyncScheduler;
import feign.RetryableException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 공공 약품 API 클라이언트가 OpenFeign 기본값(읽기 60초) 대신 설정한 타임아웃을 쓰는지
 * 실제 Feign 클라이언트와 느린 로컬 HTTP 서버로 확인한다(LLD-0100).
 * 서버는 1초 뒤에 응답한다. 검색 읽기 타임아웃(300ms)에서는 끊기고 배치 읽기 타임아웃(5초)에서는 받는다.
 */
@SpringBootTest(
        classes = MedicineApiClientTimeoutTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "medicine.api.service-key=test-service-key",
                "medicine.api.connect-timeout=1s",
                "medicine.api.read-timeout=300ms",
                "medicine.api.batch-read-timeout=5s"
        }
)
@DisplayName("공공 약품 API 클라이언트 타임아웃 테스트")
class MedicineApiClientTimeoutTest {

    private static final long SERVER_DELAY_MS = 1_000;
    private static final HttpServer SLOW_SERVER = startSlowServer();

    @Autowired private MedicineApiClient medicineApiClient;
    @Autowired private MedicineSyncScheduler medicineSyncScheduler;
    @MockBean private MedicineUpsertService medicineUpsertService;

    @DynamicPropertySource
    static void medicineApiUrl(DynamicPropertyRegistry registry) {
        registry.add("medicine.api.url", () -> "http://localhost:" + SLOW_SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SLOW_SERVER.stop(0);
    }

    @Test
    @DisplayName("검색 호출은 응답이 읽기 타임아웃보다 늦으면 기다리지 않고 예외가 발생한다")
    void 검색_호출은_응답이_읽기_타임아웃보다_늦으면_예외가_발생한다() {
        // given
        long startedAt = System.nanoTime();

        // when & then
        assertThatThrownBy(() -> medicineApiClient.searchMedicines("test-service-key", "타이레놀", 10, 1, "json"))
                .isInstanceOf(RetryableException.class);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs).isLessThan(SERVER_DELAY_MS);
    }

    @Test
    @DisplayName("배치 동기화는 검색 읽기 타임아웃보다 늦은 응답도 배치 읽기 타임아웃 안이면 받아 저장한다")
    void 배치_동기화는_배치_읽기_타임아웃_안의_늦은_응답을_받아_저장한다() {
        // given
        given(medicineUpsertService.upsertMedicines(anyList())).willReturn(List.of());

        // when
        medicineSyncScheduler.syncMedicineData();

        // then
        then(medicineUpsertService).should().upsertMedicines(anyList());
    }

    private static HttpServer startSlowServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/getDrbEasyDrugList", exchange -> {
                try {
                    Thread.sleep(SERVER_DELAY_MS);
                    byte[] body = """
                            {"body":{"pageNo":1,"totalCount":1,"numOfRows":100,
                            "items":[{"itemSeq":"200001","itemName":"타이레놀정500밀리그람"}]}}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } catch (InterruptedException | IOException e) {
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
    @EnableFeignClients(clients = MedicineApiClient.class)
    @EnableConfigurationProperties(MedicineProperties.class)
    @Import(MedicineSyncScheduler.class)
    static class TestApplication {
    }
}
