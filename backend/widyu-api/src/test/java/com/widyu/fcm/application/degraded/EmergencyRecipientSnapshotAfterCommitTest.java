package com.widyu.fcm.application.degraded;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.widyu.member.repository.EmergencyRecipientRow;
import com.widyu.member.repository.FamilyMembershipRepository;
import com.widyu.member.repository.SeniorProfileRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 가족 변경 커밋 뒤 스냅샷 갱신 훅(LLD-0087 5.2). 트랜잭션 동기화를 직접 열고 커밋 콜백을 돌린다. */
@ExtendWith(MockitoExtension.class)
@DisplayName("가족 변경 후 긴급 수신자 스냅샷 갱신 훅 테스트")
class EmergencyRecipientSnapshotAfterCommitTest {

    @Mock private FamilyMembershipRepository memberships;
    @Mock private SeniorProfileRepository seniors;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("가족 변경 트랜잭션이 커밋되면 그 가족의 스냅샷을 읽기 전용 새 트랜잭션에서 한 번 다시 만든다")
    void 가족변경이_커밋되면_그_가족의_스냅샷을_새_트랜잭션에서_다시_만든다() {
        // given
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(memberships.findEmergencyRecipients(10L)).willReturn(
                List.of(new EmergencyRecipientRow(1L, "시니어", null, 101L, 201L, "token")));
        given(seniors.findSeniorMemberIds(10L)).willReturn(List.of(1L));
        given(redis.opsForValue()).willReturn(values);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().refreshFamilyAfterCommit(10L);
            then(memberships).should(never()).findEmergencyRecipients(any());

            // when
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // then
        then(values).should().set(eq("fcm:degraded:recipients:1"), anyString(), eq(Duration.ofHours(24)));
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        then(transactionManager).should().getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("가족 변경 트랜잭션이 롤백되면 스냅샷을 다시 만들지 않는다")
    void 가족변경이_롤백되면_스냅샷을_다시_만들지_않는다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().refreshFamilyAfterCommit(10L);

            // when
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // then
        then(transactionManager).shouldHaveNoInteractions();
        then(redis).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("커밋 뒤 갱신에서 DB 연결이 안 돼도 예외를 호출자에게 내지 않는다")
    void 커밋뒤_갱신에서_DB연결이_안돼도_예외를_내지_않는다() {
        // given
        given(transactionManager.getTransaction(any())).willThrow(new CannotCreateTransactionException("db down"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().refreshFamilyAfterCommit(10L);

            // when / then
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit)).doesNotThrowAnyException();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("가족이 해체되면 커밋 뒤 잡아 둔 시니어들의 스냅샷 키를 지운다")
    void 가족이_해체되면_커밋뒤_시니어들의_스냅샷_키를_지운다() {
        // given
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().evictAfterCommit(10L, List.of(1L, 2L));
            then(redis).shouldHaveNoInteractions();

            // when
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // then
        then(redis).should().delete(List.of("fcm:degraded:recipients:1", "fcm:degraded:recipients:2"));
    }

    private EmergencyRecipientSnapshotService service() {
        return new EmergencyRecipientSnapshotService(memberships, seniors, redis, new ObjectMapper(),
                new FcmDegradedProperties(true, Duration.ofHours(24), Duration.ofSeconds(60), 10000L, 100),
                transactionManager);
    }
}
