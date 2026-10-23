package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import java.io.File;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("센서 시간 범위 내보내기 워커")
class SensorRangeExportWorkerTest {

    @Mock private SensorRangeExportTransactions transactions;
    @Mock private SensorRangeExportAssembler assembler;
    @Mock private S3Service s3Service;

    @Test
    @DisplayName("ZIP 업로드가 실패하면 작업을 실패로 남기고 임시 파일을 지운다")
    void ZIP_업로드가_실패하면_작업을_실패로_남기고_임시_파일을_지운다() {
        // given
        SensorRangeExport export = SensorRangeExport.builder()
                .exportId("sexp-test")
                .memberId(7L)
                .startAtMs(1000L)
                .endAtMs(2000L)
                .status(SensorRangeExportStatus.RUNNING)
                .requestedAtMs(1000L)
                .build();
        ReflectionTestUtils.setField(export, "id", 1L);
        ReflectionTestUtils.setField(export, "startedAtMs", 1234L);
        given(transactions.claim()).willReturn(Optional.of(export));
        willThrow(new IllegalStateException("S3 failure"))
                .given(s3Service).uploadLocalFile(any(), any(File.class), eq("application/zip"));

        // when
        new SensorRangeExportWorker(transactions, assembler, s3Service).poll();

        // then
        then(transactions).should().fail(1L, 1234L, "IllegalStateException");
        ArgumentCaptor<File> uploadedFile = ArgumentCaptor.forClass(File.class);
        then(s3Service).should().uploadLocalFile(any(), uploadedFile.capture(), eq("application/zip"));
        assertThat(uploadedFile.getValue()).doesNotExist();
    }
}
