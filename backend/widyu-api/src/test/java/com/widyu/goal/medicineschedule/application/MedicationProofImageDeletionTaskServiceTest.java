package com.widyu.goal.medicineschedule.application;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.global.infrastructure.s3.S3DeleteResult;
import com.widyu.global.infrastructure.s3.S3Service;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("MedicationProofImageDeletionTaskService 단위 테스트")
class MedicationProofImageDeletionTaskServiceTest {

    @Mock private MedicationProofImageDeletionTaskTransactionService transactionService;
    @Mock private S3Service s3Service;

    @InjectMocks private MedicationProofImageDeletionTaskService taskService;

    @Test
    @DisplayName("선점한 삭제 작업의 S3 실패 유형을 별도 트랜잭션 기록 서비스에 전달한다")
    void 선점한_삭제_작업의_S3_실패_유형을_별도_트랜잭션_기록_서비스에_전달한다() {
        // given
        MedicationProofImageDeletionTaskTransactionService.DeletionCommand command =
                new MedicationProofImageDeletionTaskTransactionService.DeletionCommand(1L, "medication-proof/1.jpg", 3);
        given(transactionService.claimForProcessing(1L)).willReturn(Optional.of(command));
        given(s3Service.deleteFileByKey("medication-proof/1.jpg"))
                .willReturn(S3DeleteResult.failure("S3Exception"));

        // when
        taskService.process(1L);

        // then
        verify(transactionService).recordResult(1L, 3, false, "S3Exception");
    }

    @Test
    @DisplayName("이미 다른 작업자가 선점한 작업은 S3 삭제를 호출하지 않는다")
    void 이미_다른_작업자가_선점한_작업은_S3_삭제를_호출하지_않는다() {
        // given
        given(transactionService.claimForProcessing(1L)).willReturn(Optional.empty());

        // when
        taskService.process(1L);

        // then
        verify(transactionService).claimForProcessing(1L);
        verifyNoInteractions(s3Service);
    }
}
