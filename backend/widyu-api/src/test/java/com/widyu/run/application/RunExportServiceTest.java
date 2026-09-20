package com.widyu.run.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunExport;
import com.widyu.run.RunExportStatus;
import com.widyu.run.dto.response.RunExportResponse;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunExportRepository;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunExportService 단위 테스트")
class RunExportServiceTest {

    private static final String RUN_ID = RunExportFixture.RUN_ID;
    private static final String EXPORT_ID = "exp-0f3a";

    @Mock private RunExportRepository runExportRepository;
    @Mock private CollectionRunRepository collectionRunRepository;
    @Mock private S3DirectUploadService s3DirectUploadService;

    @Test
    @DisplayName("닫힌 회차에 요청하면 대기 상태의 잡을 만든다")
    void 닫힌_회차에_요청하면_대기_상태의_잡을_만든다() {
        // given
        given(collectionRunRepository.findByRunId(RUN_ID))
                .willReturn(Optional.of(RunExportFixture.closedRun()));
        given(runExportRepository.findByRunIdAndStatusInOrderByIdAsc(any(), any()))
                .willReturn(List.of());
        given(runExportRepository.save(any(RunExport.class))).willAnswer(i -> i.getArgument(0));

        // when
        RunExportResponse response = service().request(RUN_ID);

        // then
        assertThat(response.status()).isEqualTo(RunExportStatus.QUEUED);
        assertThat(response.exportId()).startsWith("exp-");
        assertThat(response.runId()).isEqualTo(RUN_ID);
        assertThat(response.fileName()).isEqualTo("run_%s.zip".formatted(RUN_ID));
    }

    @Test
    @DisplayName("열린 회차에 요청하면 예외가 발생한다")
    void 열린_회차에_요청하면_예외가_발생한다() {
        // given
        CollectionRun open = CollectionRun.builder()
                .runId(RUN_ID)
                .member(RunExportFixture.member())
                .collectionMode("research")
                .startedAtMs(RunExportFixture.STARTED_AT_MS)
                .status(CollectionRunStatus.OPEN)
                .build();
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(open));

        // when & then
        // 자료가 계속 들어오는 중이라 목록을 확정할 수 없다.
        assertThatThrownBy(() -> service().request(RUN_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_NOT_CLOSED);
        then(runExportRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("진행 중인 잡이 있으면 새로 만들지 않고 그것을 돌려준다")
    void 진행_중인_잡이_있으면_새로_만들지_않고_그것을_돌려준다() {
        // given
        given(collectionRunRepository.findByRunId(RUN_ID))
                .willReturn(Optional.of(RunExportFixture.closedRun()));
        given(runExportRepository.findByRunIdAndStatusInOrderByIdAsc(any(), any()))
                .willReturn(List.of(queuedExport()));

        // when
        RunExportResponse response = service().request(RUN_ID);

        // then
        assertThat(response.exportId()).isEqualTo(EXPORT_ID);
        then(runExportRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("완료된 잡을 조회하면 내려받기 URL과 만료 시각을 함께 준다")
    void 완료된_잡을_조회하면_내려받기_URL과_만료_시각을_함께_준다() {
        // given
        RunExport export = queuedExport();
        export.finish("exports/run-0f3a/exp-0f3a/run_run-0f3a.zip", 18_234L, "e".repeat(64),
                RunExportFixture.ENDED_AT_MS);
        given(runExportRepository.findByExportIdAndRunId(EXPORT_ID, RUN_ID))
                .willReturn(Optional.of(export));
        given(s3DirectUploadService.presignGet(anyString(), any(Duration.class)))
                .willReturn("https://s3.example/run.zip?sig=1");
        long before = System.currentTimeMillis();

        // when
        RunExportResponse response = service().status(RUN_ID, EXPORT_ID);

        // then
        assertThat(response.status()).isEqualTo(RunExportStatus.DONE);
        assertThat(response.downloadUrl()).isEqualTo("https://s3.example/run.zip?sig=1");
        assertThat(response.bytes()).isEqualTo(18_234L);
        // 15분짜리를 조회 시점에 발급한다. 저장해 두면 만료된 URL을 돌려주게 된다.
        assertThat(response.downloadExpiresAtMs())
                .isBetween(before + Duration.ofMinutes(15).toMillis(),
                        System.currentTimeMillis() + Duration.ofMinutes(15).toMillis());
    }

    @Test
    @DisplayName("다른 회차의 내보내기를 조회하면 예외가 발생한다")
    void 다른_회차의_내보내기를_조회하면_예외가_발생한다() {
        // given
        given(runExportRepository.findByExportIdAndRunId(EXPORT_ID, RUN_ID))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().status(RUN_ID, EXPORT_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_EXPORT_NOT_FOUND);
    }

    private RunExport queuedExport() {
        RunExport export = RunExport.builder()
                .exportId(EXPORT_ID)
                .runId(RUN_ID)
                .status(RunExportStatus.QUEUED)
                .requestedAtMs(RunExportFixture.STARTED_AT_MS)
                .serverBuild("test-build")
                .build();
        ReflectionTestUtils.setField(export, "id", 1L);
        return export;
    }

    private RunExportService service() {
        return new RunExportService(runExportRepository, collectionRunRepository,
                s3DirectUploadService, RunExportFixture.properties());
    }
}
