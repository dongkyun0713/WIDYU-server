package com.widyu.sensor.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.global.infrastructure.s3.S3DirectUploadService;
import com.widyu.member.Member;
import com.widyu.member.repository.MemberRepository;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import com.widyu.sensor.dto.request.SensorRangeExportRequest;
import com.widyu.sensor.dto.response.SensorRangeExportResponse;
import com.widyu.sensor.repository.SensorRangeExportRepository;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("센서 시간 범위 내보내기 요청")
class SensorRangeExportServiceTest {

    @Mock private MemberRepository memberRepository;
    @Mock private SensorRangeExportRepository exportRepository;
    @Mock private S3DirectUploadService s3DirectUploadService;

    @Test
    @DisplayName("유효한 시간 범위를 요청하면 대기 작업을 저장한다")
    void 유효한_시간_범위를_요청하면_대기_작업을_저장한다() {
        // given
        long end = System.currentTimeMillis() - 1000;
        SensorRangeExportRequest request = new SensorRangeExportRequest(end - 60_000, end);
        given(memberRepository.findByIdForUpdate(7L)).willReturn(Optional.of(mock(Member.class)));
        given(exportRepository.findByMemberIdAndStartAtMsAndEndAtMsAndStatusInOrderByIdAsc(
                any(), any(), any(), any())).willReturn(List.of());
        given(exportRepository.save(any(SensorRangeExport.class))).willAnswer(i -> i.getArgument(0));

        // when
        SensorRangeExportResponse response = service().request(7L, request);

        // then
        assertThat(response.memberId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo(SensorRangeExportStatus.QUEUED);
        assertThat(response.startAtMs()).isEqualTo(request.startAtMs());
        assertThat(response.endAtMs()).isEqualTo(request.endAtMs());
        assertThat(response.downloadUrl()).isNull();
    }

    @Test
    @DisplayName("종료보다 늦은 시작이나 하루 초과 범위는 거부한다")
    void 잘못된_시간_범위를_요청하면_예외가_발생한다() {
        // given
        long now = System.currentTimeMillis();

        // when & then
        assertThatThrownBy(() -> service().request(7L, new SensorRangeExportRequest(now, now)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        assertThatThrownBy(() -> service().request(7L,
                new SensorRangeExportRequest(now - 86_400_002, now - 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_EXPORT_RANGE_INVALID);
        then(memberRepository).should(never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("다른 회원 경로에서 작업을 조회하면 찾지 못한다")
    void 다른_회원의_작업을_조회하면_예외가_발생한다() {
        // given
        given(exportRepository.findByExportIdAndMemberId("sexp-test", 8L))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service().status(8L, "sexp-test"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SENSOR_EXPORT_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 회원과 구간의 진행 중 작업은 다시 만들지 않고 돌려준다")
    void 같은_구간의_진행_중_작업을_요청하면_기존_작업을_돌려준다() {
        // given
        long end = System.currentTimeMillis() - 1000;
        SensorRangeExportRequest request = new SensorRangeExportRequest(end - 1000, end);
        SensorRangeExport existing = export(7L, request.startAtMs(), request.endAtMs());
        given(memberRepository.findByIdForUpdate(7L)).willReturn(Optional.of(mock(Member.class)));
        given(exportRepository.findByMemberIdAndStartAtMsAndEndAtMsAndStatusInOrderByIdAsc(
                any(), any(), any(), any())).willReturn(List.of(existing));

        // when
        SensorRangeExportResponse response = service().request(7L, request);

        // then
        assertThat(response.exportId()).isEqualTo("sexp-test");
        then(exportRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("완료 작업은 상태 조회 시점에만 단기 다운로드 URL을 발급한다")
    void 완료_작업을_조회하면_단기_다운로드_URL을_발급한다() {
        // given
        SensorRangeExport export = export(7L, 1000L, 2000L);
        export.finish("exports/sensor-range/7/sexp-test.zip", 512L, "a".repeat(64), 3000L);
        given(exportRepository.findByExportIdAndMemberId("sexp-test", 7L))
                .willReturn(Optional.of(export));
        given(s3DirectUploadService.presignGet(anyString(), any(Duration.class)))
                .willReturn("https://example.test/export.zip");

        // when
        SensorRangeExportResponse response = service().status(7L, "sexp-test");

        // then
        assertThat(response.downloadUrl()).isEqualTo("https://example.test/export.zip");
        assertThat(response.downloadExpiresAtMs()).isGreaterThan(System.currentTimeMillis());
        assertThat(response.bytes()).isEqualTo(512L);
    }

    private SensorRangeExport export(Long memberId, long startAtMs, long endAtMs) {
        return SensorRangeExport.builder()
                .exportId("sexp-test")
                .memberId(memberId)
                .startAtMs(startAtMs)
                .endAtMs(endAtMs)
                .status(SensorRangeExportStatus.QUEUED)
                .requestedAtMs(startAtMs)
                .build();
    }

    private SensorRangeExportService service() {
        return new SensorRangeExportService(memberRepository, exportRepository,
                s3DirectUploadService, SensorConfigFixture.properties());
    }
}
