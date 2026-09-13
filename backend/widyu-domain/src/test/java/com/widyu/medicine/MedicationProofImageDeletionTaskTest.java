package com.widyu.medicine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MedicationProofImageDeletionTask 단위 테스트")
class MedicationProofImageDeletionTaskTest {

    @Test
    @DisplayName("삭제 실패가 다섯 번이면 더 이상 재시도하지 않는 종료 상태가 된다")
    void 삭제_실패가_다섯_번이면_더_이상_재시도하지_않는_종료_상태가_된다() {
        // given
        MedicationProofImageDeletionTask task = MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg");

        // when
        for (int attempt = 0; attempt < 5; attempt++) {
            task.claim(LocalDateTime.now());
            task.fail("S3Exception");
        }

        // then
        assertThat(task.getStatus()).isEqualTo(MedicationProofImageDeletionTaskStatus.FAILED);
        assertThat(task.getRetryCount()).isEqualTo(5);
        assertThat(task.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("이전 선점 시도의 결과는 새 선점 시도의 작업 상태를 변경하지 못한다")
    void 이전_선점_시도의_결과는_새_선점_시도의_작업_상태를_변경하지_못한다() {
        // given
        MedicationProofImageDeletionTask task = MedicationProofImageDeletionTask.pending(1L, "medication-proof/1.jpg");
        task.claim(LocalDateTime.now());
        int firstAttempt = task.getProcessingAttempt();
        task.claim(LocalDateTime.now());

        // when
        boolean previousAttemptCanWrite = task.isProcessing(firstAttempt);

        // then
        assertThat(previousAttemptCanWrite).isFalse();
    }
}
