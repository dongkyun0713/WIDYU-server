package com.widyu.global.infrastructure.s3.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.widyu.global.infrastructure.s3.S3DeleteResult;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.global.storage.S3ObjectDeletionTask;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3ObjectDeletionTaskService 단위 테스트")
class S3ObjectDeletionTaskServiceTest {

    @Mock private S3ObjectDeletionTaskTransactionService transactionService;
    @Mock private S3ObjectDeletionTaskRepository repository;
    @Mock private S3Service s3Service;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private S3ObjectDeletionTaskService taskService;

    @Test
    @DisplayName("선점한 삭제 작업의 S3 실패 유형을 별도 트랜잭션 기록 서비스에 전달한다")
    void 선점한_삭제_작업의_S3_실패_유형을_별도_트랜잭션_기록_서비스에_전달한다() {
        // given
        S3ObjectDeletionTaskTransactionService.DeletionCommand command =
                new S3ObjectDeletionTaskTransactionService.DeletionCommand(1L, "medication-proof/1.jpg", 3);
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

    @Test
    @DisplayName("파일 삭제를 예약하면 버킷 안 파일만 삭제 작업으로 저장하고 삭제 이벤트를 발행한다")
    void 파일_삭제를_예약하면_버킷_안_파일만_작업으로_저장하고_이벤트를_발행한다() {
        // given
        given(s3Service.extractObjectKey("https://cdn.example.com/profile/a.jpg")).willReturn("profile/a.jpg");
        willThrow(new IllegalArgumentException()).given(s3Service).extractObjectKey("https://other.example.com/b.jpg");
        given(repository.saveAll(any())).willAnswer(invocation -> {
            List<S3ObjectDeletionTask> tasks = invocation.getArgument(0);
            ReflectionTestUtils.setField(tasks.get(0), "id", 21L);
            return tasks;
        });

        // when
        taskService.schedule(1L, Arrays.asList(
                "https://cdn.example.com/profile/a.jpg", "https://other.example.com/b.jpg", null, " "));

        // then
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<S3ObjectDeletionTask>> tasksCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(tasksCaptor.capture());
        assertThat(tasksCaptor.getValue()).extracting(S3ObjectDeletionTask::getObjectKey)
                .containsExactly("profile/a.jpg");
        ArgumentCaptor<S3ObjectDeletionEvent> eventCaptor = ArgumentCaptor.forClass(S3ObjectDeletionEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().taskIds()).containsExactly(21L);
    }

    @Test
    @DisplayName("삭제할 파일 주소가 없으면 작업을 저장하지 않고 이벤트도 발행하지 않는다")
    void 삭제할_파일_주소가_없으면_작업을_저장하지_않는다() {
        // when
        taskService.schedule(1L, List.of());

        // then
        verifyNoInteractions(repository, eventPublisher);
    }
}
