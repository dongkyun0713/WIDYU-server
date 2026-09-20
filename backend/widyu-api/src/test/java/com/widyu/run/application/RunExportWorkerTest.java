package com.widyu.run.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import com.widyu.run.repository.CollectionRunRepository;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunExportWorker 단위 테스트")
class RunExportWorkerTest {

    @Mock private RunExportTransactions transactions;
    @Mock private CollectionRunRepository collectionRunRepository;
    @Mock private RunExportAssembler runExportAssembler;
    @Mock private S3Service s3Service;

    @InjectMocks private RunExportWorker runExportWorker;

    @Test
    @DisplayName("큐가 비어 있으면 아무것도 하지 않는다")
    void 큐가_비어_있으면_아무것도_하지_않는다() {
        // given
        given(transactions.claim()).willReturn(Optional.empty());

        // when
        runExportWorker.poll();

        // then
        then(s3Service).should(never()).uploadLocalFile(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("다른 워커가 먼저 집으면 조립하지 않는다")
    void 다른_워커가_먼저_집으면_조립하지_않는다() throws IOException {
        // given
        // 조건부 UPDATE가 0건이면 이미 다른 워커가 선점한 것이라 빈 값이 온다.
        given(transactions.claim()).willReturn(Optional.empty());

        // when
        runExportWorker.poll();

        // then
        then(runExportAssembler).should(never()).build(any(), any());
    }

    @Test
    @DisplayName("조립이 실패하면 실패 상태와 예외 클래스명을 남긴다")
    void 조립이_실패하면_실패_상태와_예외_클래스명을_남긴다() throws IOException {
        // given
        RunExport export = queuedExport();
        given(transactions.claim()).willReturn(Optional.of(export));
        given(collectionRunRepository.findByRunId(RunExportFixture.RUN_ID))
                .willReturn(Optional.of(RunExportFixture.closedRun()));
        willThrow(new IOException("디스크 오류")).given(runExportAssembler).build(any(), any());

        // when
        runExportWorker.poll();

        // then
        // 자료 값이 아니라 예외 클래스명만 남긴다.
        then(transactions).should().fail(1L, "IOException");
        then(transactions).should(never()).finish(anyLong(), anyString(), anyLong(), anyString());
        then(s3Service).should(never()).uploadLocalFile(anyString(), any(), anyString());
    }

    private RunExport queuedExport() {
        RunExport export = RunExport.builder()
                .exportId("exp-0f3a")
                .runId(RunExportFixture.RUN_ID)
                .status(RunExportStatus.QUEUED)
                .requestedAtMs(RunExportFixture.STARTED_AT_MS)
                .serverBuild("test-build")
                .build();
        ReflectionTestUtils.setField(export, "id", 1L);
        return export;
    }
}
