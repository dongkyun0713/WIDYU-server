package com.widyu.goal.medicineschedule.application;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.global.infrastructure.s3.S3DeleteResult;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.goal.medicineschedule.event.MedicationProofImageDeletionListener;
import com.widyu.goal.medicineschedule.event.MedicationProofImagesDeletionEvent;
import com.widyu.goal.medicineschedule.repository.MedicationProofImageDeletionTaskRepository;
import com.widyu.medicine.MedicationProofImageDeletionTask;
import com.widyu.medicine.MedicationProofImageDeletionTaskStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest
@ActiveProfiles("test")
@Import({
        JpaAuditingConfig.class,
        MedicationProofImageDeletionTaskTransactionService.class,
        MedicationProofImageDeletionTaskService.class,
        MedicationProofImageDeletionListener.class
})
@DisplayName("MedicationProofImageDeletionTaskTransactionService 영속화 테스트")
class MedicationProofImageDeletionTaskTransactionServiceTest {

    @Autowired private MedicationProofImageDeletionTaskRepository repository;
    @Autowired private MedicationProofImageDeletionTaskTransactionService transactionService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;
    @MockBean private S3Service s3Service;

    @Test
    @DisplayName("선점과 완료 결과는 호출 트랜잭션이 끝난 뒤에도 별도 트랜잭션으로 저장된다")
    void 선점과_완료_결과는_호출_트랜잭션이_끝난_뒤에도_별도_트랜잭션으로_저장된다() {
        // given
        MedicationProofImageDeletionTask task = repository.saveAndFlush(
                MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg"));
        Long taskId = task.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // when
        MedicationProofImageDeletionTaskTransactionService.DeletionCommand command =
                transactionService.claimForProcessing(taskId).orElseThrow();
        transactionService.recordResult(command.id(), command.attempt(), true, null);

        // then
        TestTransaction.start();
        MedicationProofImageDeletionTask completed = repository.findById(taskId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(MedicationProofImageDeletionTaskStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("선점된 작업은 같은 시도에서 한 번만 처리할 수 있다")
    void 선점된_작업은_같은_시도에서_한_번만_처리할_수_있다() {
        // given
        MedicationProofImageDeletionTask task = repository.saveAndFlush(
                MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg"));
        Long taskId = task.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();

        // when
        boolean firstClaimed = transactionService.claimForProcessing(taskId).isPresent();
        boolean secondClaimed = transactionService.claimForProcessing(taskId).isPresent();

        // then
        assertThat(firstClaimed).isTrue();
        assertThat(secondClaimed).isFalse();
        TestTransaction.start();
    }

    @Test
    @DisplayName("커밋 뒤 이벤트는 트랜잭션 없이 S3를 호출하고 완료 상태를 저장한다")
    void 커밋_뒤_이벤트는_트랜잭션_없이_S3를_호출하고_완료_상태를_저장한다() {
        // given
        TestTransaction.end();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        AtomicBoolean s3CalledWithoutTransaction = new AtomicBoolean(false);
        given(s3Service.deleteFileByKey("medication-proof/1.jpg")).willAnswer(invocation -> {
            s3CalledWithoutTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            return S3DeleteResult.success();
        });

        // when
        Long taskId = transactionTemplate.execute(status -> {
            MedicationProofImageDeletionTask task = repository.saveAndFlush(
                    MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg"));
            eventPublisher.publishEvent(new MedicationProofImagesDeletionEvent(List.of(task.getId())));
            return task.getId();
        });

        // then
        MedicationProofImageDeletionTask completed = repository.findById(taskId).orElseThrow();
        assertThat(s3CalledWithoutTransaction.get()).isTrue();
        assertThat(completed.getStatus()).isEqualTo(MedicationProofImageDeletionTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("롤백된 이벤트는 S3 삭제를 호출하거나 작업을 저장하지 않는다")
    void 롤백된_이벤트는_S3_삭제를_호출하거나_작업을_저장하지_않는다() {
        // given
        TestTransaction.end();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        long taskCountBeforeRollback = repository.count();

        // when
        transactionTemplate.executeWithoutResult(status -> {
            MedicationProofImageDeletionTask task = repository.saveAndFlush(
                    MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg"));
            eventPublisher.publishEvent(new MedicationProofImagesDeletionEvent(List.of(task.getId())));
            status.setRollbackOnly();
        });

        // then
        assertThat(repository.count()).isEqualTo(taskCountBeforeRollback);
        verifyNoInteractions(s3Service);
    }

    @Test
    @DisplayName("임대가 만료된 작업은 다시 선점하고 이전 시도의 늦은 결과는 무시한다")
    void 임대가_만료된_작업은_다시_선점하고_이전_시도의_늦은_결과는_무시한다() {
        // given
        MedicationProofImageDeletionTask task = repository.saveAndFlush(
                MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg"));
        Long taskId = task.getId();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        MedicationProofImageDeletionTaskTransactionService.DeletionCommand first =
                transactionService.claimForProcessing(taskId).orElseThrow();

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> ReflectionTestUtils.setField(
                repository.findById(taskId).orElseThrow(), "leaseExpiresAt", LocalDateTime.now().minusMinutes(1)));

        // when
        MedicationProofImageDeletionTaskTransactionService.DeletionCommand second =
                transactionService.claimForProcessing(taskId).orElseThrow();
        transactionService.recordResult(first.id(), first.attempt(), true, null);

        // then
        TestTransaction.start();
        MedicationProofImageDeletionTask processing = repository.findById(taskId).orElseThrow();
        assertThat(second.attempt()).isGreaterThan(first.attempt());
        assertThat(processing.getStatus()).isEqualTo(MedicationProofImageDeletionTaskStatus.PROCESSING);
        assertThat(processing.getProcessingAttempt()).isEqualTo(second.attempt());
    }
}
