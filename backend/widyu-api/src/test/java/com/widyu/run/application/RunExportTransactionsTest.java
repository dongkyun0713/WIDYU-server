package com.widyu.run.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import com.widyu.run.repository.RunExportRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunExportTransactions 단위 테스트")
class RunExportTransactionsTest {

    @Mock private RunExportRepository runExportRepository;

    @Test
    @DisplayName("만료된 실행 작업을 대기로 되돌린 뒤 가장 오래된 작업을 선점한다")
    void 만료된_실행_작업을_대기로_되돌린_뒤_가장_오래된_작업을_선점한다() {
        // given
        RunExport queued = queuedExport();
        given(runExportRepository.findFirstByStatusOrderByRequestedAtMsAscIdAsc(RunExportStatus.QUEUED))
                .willReturn(Optional.of(queued));
        given(runExportRepository.claim(eq(1L), anyLong())).willReturn(1);
        given(runExportRepository.findById(1L)).willReturn(Optional.of(queued));
        long before = System.currentTimeMillis();

        // when
        Optional<RunExport> claimed = transactions().claim();

        // then
        assertThat(claimed).contains(queued);
        ArgumentCaptor<Long> expiredBefore = ArgumentCaptor.forClass(Long.class);
        InOrder order = inOrder(runExportRepository);
        order.verify(runExportRepository).requeueExpiredRunning(expiredBefore.capture());
        order.verify(runExportRepository)
                .findFirstByStatusOrderByRequestedAtMsAscIdAsc(RunExportStatus.QUEUED);
        order.verify(runExportRepository).claim(eq(1L), anyLong());
        order.verify(runExportRepository).findById(1L);
        assertThat(expiredBefore.getValue())
                .isBetween(before - 900_000L, System.currentTimeMillis() - 900_000L);
    }

    @Test
    @DisplayName("다른 워커가 먼저 선점하면 작업을 돌려주지 않는다")
    void 다른_워커가_먼저_선점하면_작업을_돌려주지_않는다() {
        // given
        RunExport queued = queuedExport();
        given(runExportRepository.findFirstByStatusOrderByRequestedAtMsAscIdAsc(RunExportStatus.QUEUED))
                .willReturn(Optional.of(queued));
        given(runExportRepository.claim(eq(1L), anyLong())).willReturn(0);

        // when
        Optional<RunExport> claimed = transactions().claim();

        // then
        assertThat(claimed).isEmpty();
        then(runExportRepository).should(never()).findById(1L);
    }

    private RunExportTransactions transactions() {
        return new RunExportTransactions(runExportRepository, RunExportFixture.properties());
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
