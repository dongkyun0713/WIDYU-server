package com.widyu.goal.medicineschedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.properties.MedicineProperties;
import com.widyu.goal.medicineschedule.client.MedicineApiClient;
import com.widyu.goal.medicineschedule.dto.external.MedicineApiResponse;
import com.widyu.goal.medicineschedule.dto.response.MedicineSearchResponse;
import com.widyu.goal.medicineschedule.repository.MedicineRepository;
import com.widyu.medicine.Medicine;
import feign.Request;
import feign.RetryableException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 약품 검색의 외부 API fallback을 실제 H2 트랜잭션 위에서 확인한다(LLD-0100). 공공 API 클라이언트만 대역이다.
 * 테스트 메서드가 트랜잭션을 열지 않아야 서비스의 트랜잭션 경계와 커밋 시점 예외를 그대로 볼 수 있다.
 * 키워드는 1글자로 둔다. 2글자 이상은 MySQL FULLTEXT 쿼리라 H2에서 실행되지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaAuditingConfig.class, ExternalMedicineService.class, MedicineUpsertService.class})
@DisplayName("약품 검색 외부 API fallback 트랜잭션 경계 통합 테스트")
class MedicineSearchFallbackIntegrationTest {

    private static final String KEYWORD = "타";
    private static final String SERVICE_KEY = "secret-service-key";
    // 다른 요청이 먼저 저장하는 약품과, 이번 검색에서만 새로 받는 약품
    private static final MedicineApiResponse.MedicineItem CONTENDED =
            item("200001", "타이레놀정500밀리그람");
    private static final MedicineApiResponse.MedicineItem FRESH =
            item("200002", "타이레놀8시간이알서방정");

    @Autowired private ExternalMedicineService externalMedicineService;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @SpyBean private MedicineRepository medicineRepository;
    @MockBean private MedicineApiClient medicineApiClient;
    @MockBean private MedicineProperties medicineProperties;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @AfterEach
    void cleanup() {
        medicineRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("존재 확인 뒤 다른 요청이 같은 약품을 먼저 저장해 충돌해도 함께 받은 신규 약품까지 저장해 반환한다")
    void 존재_확인_뒤_다른_요청이_같은_약품을_먼저_저장해_충돌해도_신규_약품까지_저장해_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        AtomicBoolean transactionActiveDuringApiCall = new AtomicBoolean(true);
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    transactionActiveDuringApiCall.set(TransactionSynchronizationManager.isActualTransactionActive());
                    otherRequestSaves(CONTENDED);
                    return apiResponse(CONTENDED, FRESH);
                });
        // 첫 존재 확인은 다른 요청의 커밋 직전이라 아무것도 보지 못하고, 다시 저장할 때는 그 약품을 본다.
        willReturn(Set.of()).willReturn(Set.of(CONTENDED.itemSeq()))
                .given(medicineRepository).findItemSeqsByItemSeqIn(anyList());

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(transactionActiveDuringApiCall).isFalse();
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactlyInAnyOrder(CONTENDED.itemName(), FRESH.itemName());
        assertThat(medicineRepository.findByItemSeqIn(List.of(CONTENDED.itemSeq(), FRESH.itemSeq()))).hasSize(2);
    }

    @Test
    @DisplayName("요청 범위 EntityManager가 열린 OSIV 환경에서도 유일 제약 충돌 뒤 신규 약품까지 저장해 반환한다")
    void OSIV_환경에서도_유일_제약_충돌_뒤_신규_약품까지_저장해_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    otherRequestSaves(CONTENDED);
                    return apiResponse(CONTENDED, FRESH);
                });
        willReturn(Set.of()).willReturn(Set.of(CONTENDED.itemSeq()))
                .given(medicineRepository).findItemSeqsByItemSeqIn(anyList());
        // 운영은 OSIV가 켜져 있어 요청 내내 같은 EntityManager를 쓴다. 실패한 INSERT가 남긴 엔티티가 재시도·재조회를 깨지 않아야 한다.
        EntityManager requestScoped = entityManagerFactory.createEntityManager();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new EntityManagerHolder(requestScoped));

        // when
        MedicineSearchResponse response;
        try {
            response = externalMedicineService.searchAndSaveMedicines(KEYWORD);
        } finally {
            TransactionSynchronizationManager.unbindResource(entityManagerFactory);
            requestScoped.close();
        }

        // then
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactlyInAnyOrder(CONTENDED.itemName(), FRESH.itemName());
    }

    @Test
    @DisplayName("다시 저장할 때도 충돌하면 저장을 포기하고 이미 저장된 약품을 반환한다")
    void 다시_저장할_때도_충돌하면_이미_저장된_약품을_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    otherRequestSaves(CONTENDED);
                    return apiResponse(CONTENDED);
                });
        willReturn(Set.of()).given(medicineRepository).findItemSeqsByItemSeqIn(anyList());

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactly(CONTENDED.itemName());
    }

    @Test
    @DisplayName("존재 확인 전에 다른 요청이 같은 약품을 저장했으면 저장을 건너뛰고 그 약품을 반환한다")
    void 존재_확인_전에_다른_요청이_같은_약품을_저장했으면_그_약품을_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    otherRequestSaves(CONTENDED);
                    return apiResponse(CONTENDED);
                });

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactly(CONTENDED.itemName());
        assertThat(medicineRepository.findByItemSeqIn(List.of(CONTENDED.itemSeq()))).hasSize(1);
    }

    @Test
    @DisplayName("외부 API가 읽기 타임아웃으로 실패하면 빈 목록을 반환하고 서비스 키를 로그에 남기지 않는다")
    void 외부_API가_읽기_타임아웃으로_실패하면_빈_목록을_반환하고_서비스_키를_로그에_남기지_않는다(CapturedOutput output) {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(eq(SERVICE_KEY), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willThrow(readTimeout());

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(response.medicines()).isEmpty();
        assertThat(output).contains("errorType=RetryableException");
        assertThat(output).doesNotContain(SERVICE_KEY);
    }

    @Test
    @DisplayName("신규 약품이 중복 외의 무결성 제약을 어기면 빈 목록으로 숨기지 않고 예외가 발생한다")
    void 신규_약품이_중복_외의_무결성_제약을_어기면_예외가_발생한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willReturn(apiResponse(item("200003", null)));

        // when & then
        assertThatThrownBy(() -> externalMedicineService.searchAndSaveMedicines(KEYWORD))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("신규 약품 저장이 DB 오류로 실패하면 빈 목록으로 숨기지 않고 예외가 발생한다")
    void 신규_약품_저장이_DB_오류로_실패하면_예외가_발생한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willReturn(apiResponse(FRESH));
        willThrow(new CannotAcquireLockException("lock wait timeout"))
                .given(medicineRepository).findItemSeqsByItemSeqIn(anyList());

        // when & then
        assertThatThrownBy(() -> externalMedicineService.searchAndSaveMedicines(KEYWORD))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    // 외부 API를 기다리는 동안 다른 요청(다른 스레드)이 같은 itemSeq를 저장하고 커밋한다.
    private void otherRequestSaves(MedicineApiResponse.MedicineItem item) {
        CompletableFuture.runAsync(() -> medicineRepository.saveAndFlush(
                Medicine.create(item.itemSeq(), item.itemName(), item.entpName(), null, null, null))).join();
    }

    private MedicineProperties.Api apiProperties() {
        return new MedicineProperties.Api(
                "http://medicine.test", SERVICE_KEY, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(30));
    }

    private static MedicineApiResponse.MedicineItem item(String itemSeq, String itemName) {
        return new MedicineApiResponse.MedicineItem("제약사", itemName, itemSeq, null, null, null);
    }

    private MedicineApiResponse apiResponse(MedicineApiResponse.MedicineItem... items) {
        List<MedicineApiResponse.MedicineItem> list = Arrays.asList(items);
        return new MedicineApiResponse(null, new MedicineApiResponse.Body(1, list.size(), 10, list, null));
    }

    // Feign이 읽기 타임아웃에 던지는 예외. 메시지와 요청 URL에 serviceKey가 들어 있다.
    private RetryableException readTimeout() {
        String url = "http://medicine.test/getDrbEasyDrugList?serviceKey=" + SERVICE_KEY;
        Request request = Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out executing GET " + url, Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }
}
