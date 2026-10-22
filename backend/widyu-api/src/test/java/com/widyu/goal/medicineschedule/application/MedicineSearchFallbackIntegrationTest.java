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
    private static final String ITEM_SEQ = "200001";
    private static final String ITEM_NAME = "타이레놀정500밀리그람";
    private static final String SERVICE_KEY = "secret-service-key";

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
    @DisplayName("존재 확인 뒤 다른 요청이 같은 약품을 먼저 저장해 유일 제약과 충돌하면 저장된 약품을 반환한다")
    void 존재_확인_뒤_다른_요청이_같은_약품을_먼저_저장해_충돌하면_저장된_약품을_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        AtomicBoolean transactionActiveDuringApiCall = new AtomicBoolean(true);
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    transactionActiveDuringApiCall.set(TransactionSynchronizationManager.isActualTransactionActive());
                    otherRequestSaves();
                    return apiResponse();
                });
        // 존재 확인과 INSERT 사이의 경합 창: 확인 시점에는 다른 요청의 커밋이 아직 보이지 않았다.
        willReturn(Set.of()).given(medicineRepository).findItemSeqsByItemSeqIn(anyList());

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(transactionActiveDuringApiCall).isFalse();
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactly(ITEM_NAME);
        assertThat(medicineRepository.findByItemSeqIn(List.of(ITEM_SEQ))).hasSize(1);
    }

    @Test
    @DisplayName("존재 확인 전에 다른 요청이 같은 약품을 저장했으면 저장을 건너뛰고 그 약품을 반환한다")
    void 존재_확인_전에_다른_요청이_같은_약품을_저장했으면_그_약품을_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    otherRequestSaves();
                    return apiResponse();
                });

        // when
        MedicineSearchResponse response = externalMedicineService.searchAndSaveMedicines(KEYWORD);

        // then
        assertThat(response.medicines())
                .extracting(MedicineSearchResponse.MedicineItem::itemName)
                .containsExactly(ITEM_NAME);
        assertThat(medicineRepository.findByItemSeqIn(List.of(ITEM_SEQ))).hasSize(1);
    }

    @Test
    @DisplayName("요청 범위 EntityManager가 열린 OSIV 환경에서도 유일 제약 충돌 뒤 저장된 약품을 반환한다")
    void OSIV_환경에서도_유일_제약_충돌_뒤_저장된_약품을_반환한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willAnswer(invocation -> {
                    otherRequestSaves();
                    return apiResponse();
                });
        willReturn(Set.of()).given(medicineRepository).findItemSeqsByItemSeqIn(anyList());
        // 운영은 OSIV가 켜져 있어 요청 내내 같은 EntityManager를 쓴다. 실패한 INSERT가 남긴 엔티티가 재조회를 깨지 않아야 한다.
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
                .containsExactly(ITEM_NAME);
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
    @DisplayName("신규 약품 저장이 유일 제약 외의 DB 오류로 실패하면 빈 목록으로 숨기지 않고 예외가 발생한다")
    void 신규_약품_저장이_유일_제약_외의_DB_오류로_실패하면_예외가_발생한다() {
        // given
        given(medicineProperties.api()).willReturn(apiProperties());
        given(medicineApiClient.searchMedicines(anyString(), eq(KEYWORD), anyInt(), anyInt(), anyString()))
                .willReturn(apiResponse());
        willThrow(new CannotAcquireLockException("lock wait timeout"))
                .given(medicineRepository).findItemSeqsByItemSeqIn(anyList());

        // when & then
        assertThatThrownBy(() -> externalMedicineService.searchAndSaveMedicines(KEYWORD))
                .isInstanceOf(CannotAcquireLockException.class);
    }

    // 외부 API를 기다리는 동안 다른 요청(다른 스레드)이 같은 itemSeq를 저장하고 커밋한다.
    private void otherRequestSaves() {
        CompletableFuture.runAsync(() -> medicineRepository.saveAndFlush(
                Medicine.create(ITEM_SEQ, ITEM_NAME, "제약사", null, null, null))).join();
    }

    private MedicineProperties.Api apiProperties() {
        return new MedicineProperties.Api(
                "http://medicine.test", SERVICE_KEY, Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(30));
    }

    private MedicineApiResponse apiResponse() {
        MedicineApiResponse.MedicineItem item = new MedicineApiResponse.MedicineItem(
                "제약사", ITEM_NAME, ITEM_SEQ, null, null, null);
        return new MedicineApiResponse(null, new MedicineApiResponse.Body(1, 1, 10, List.of(item), null));
    }

    // Feign이 읽기 타임아웃에 던지는 예외. 메시지와 요청 URL에 serviceKey가 들어 있다.
    private RetryableException readTimeout() {
        String url = "http://medicine.test/getDrbEasyDrugList?serviceKey=" + SERVICE_KEY;
        Request request = Request.create(Request.HttpMethod.GET, url, Map.of(), null, StandardCharsets.UTF_8, null);
        return new RetryableException(-1, "Read timed out executing GET " + url, Request.HttpMethod.GET,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }
}
