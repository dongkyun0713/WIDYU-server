package com.widyu.run.application;

import static com.widyu.run.application.RunExportFixture.ENDED_AT_MS;
import static com.widyu.run.application.RunExportFixture.PHONE_DEVICE;
import static com.widyu.run.application.RunExportFixture.RUN_ID;
import static com.widyu.run.application.RunExportFixture.STARTED_AT_MS;
import static com.widyu.run.application.RunExportFixture.WATCH_DEVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.widyu.decision.DecisionRecord;
import com.widyu.device.repository.DeviceHeartbeatRepository;
import com.widyu.decision.repository.DecisionRecordRepository;
import com.widyu.global.infrastructure.s3.S3Service;
import com.widyu.incident.Incident;
import com.widyu.incident.IncidentKind;
import com.widyu.incident.IncidentOutcome;
import com.widyu.incident.IncidentResponseValue;
import com.widyu.incident.IncidentState;
import com.widyu.incident.ResponseVia;
import com.widyu.incident.repository.IncidentRepository;
import com.widyu.location.raw.repository.LocationFixRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.run.repository.RunMarkerRepository;
import com.widyu.sensor.repository.ClockMappingRepository;
import com.widyu.sensor.repository.SensorBatchRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunExportAssembler 단위 테스트")
class RunExportAssemblerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private SensorBatchRepository sensorBatchRepository;
    @Mock private LocationFixRepository locationFixRepository;
    @Mock private DeviceHeartbeatRepository deviceHeartbeatRepository;
    @Mock private DecisionRecordRepository decisionRecordRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private ClockMappingRepository clockMappingRepository;
    @Mock private RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    @Mock private RunMarkerRepository runMarkerRepository;
    @Mock private S3Service s3Service;

    @TempDir Path workDir;

    @Test
    @DisplayName("합성 회차를 조립하면 형식서가 요구하는 파일과 봉투가 모두 만들어진다")
    void 합성_회차를_조립하면_형식서가_요구하는_파일과_봉투가_모두_만들어진다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        assertThat(workDir.resolve("manifest.json")).exists();
        assertThat(workDir.resolve("run.json")).exists();
        assertThat(workDir.resolve("clock_mappings.json")).exists();
        assertThat(workDir.resolve("quality.json")).exists();
        assertThat(streamNames()).containsExactlyInAnyOrder(
                "imu_watch_%s.jsonl".formatted(WATCH_DEVICE),
                "hr_%s.jsonl".formatted(WATCH_DEVICE),
                "location_%s.jsonl".formatted(PHONE_DEVICE),
                "heartbeat_%s.jsonl".formatted(PHONE_DEVICE),
                "incidents.jsonl");

        keepZipIfRequested();
    }

    @Test
    @DisplayName("판정 기록은 streams 밖 decisions.jsonl에 계약 필드 그대로 기록된다")
    void 판정_기록은_결정_파일에_기록된다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        List<JsonNode> decisions = readLines(workDir.resolve("decisions.jsonl"));
        assertThat(decisions).hasSize(3);
        assertThat(decisions.get(0).get("decision_id").asText()).isEqualTo("dec-01");
        assertThat(decisions.get(0).get("stream_ids_used")).containsExactly(
                MAPPER.getNodeFactory().textNode("01j8zimu000000000000000001"));
        assertThat(decisions.get(0).get("decision_output").asText()).isEqualTo("ALERT");
        assertThat(decisions.get(0).get("alert_delivered").asBoolean()).isTrue();
        assertThat(decisions.get(1).get("decision_output").asText())
                .isEqualTo("ABSTAIN_INSUFFICIENT_INPUT");
        assertThat(decisions.get(1).get("alert_at_ms").isNull()).isTrue();
    }

    @Test
    @DisplayName("심박 판정 줄에만 판정 사유와 심박 값이 evidence로 붙는다")
    void 심박_판정_줄에만_판정_사유와_심박_값이_붙는다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        List<JsonNode> decisions = readLines(workDir.resolve("decisions.jsonl"));
        JsonNode evidence = decisions.get(2).get("evidence");
        assertThat(decisions.get(2).get("decider_id").asText()).isEqualTo("widyu-ai-hr");
        assertThat(evidence.get("hr_bpm").asInt()).isEqualTo(185);
        assertThat(evidence.get("hr_measured_at_ms").asLong()).isEqualTo(STARTED_AT_MS + 30_000L);
        assertThat(evidence.get("hr_accuracy").asText()).isEqualTo("HIGH");
        assertThat(evidence.get("reason").asText()).isEqualTo("연속 3회 임계 초과");
        // 낙상 행에는 붙이지 않는다. 심박 값이 없는 판정은 근거로 내보낼 것이 없다(LLD-0053 3절).
        assertThat(decisions.get(0).has("evidence")).isFalse();
        assertThat(decisions.get(1).has("evidence")).isFalse();
    }

    @Test
    @DisplayName("스트림 줄에서 서버가 붙인 키를 빼면 원문과 값이 같다")
    void 스트림_줄에서_서버가_붙인_키를_빼면_원문과_값이_같다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        List<JsonNode> lines = readStream("imu_watch_%s.jsonl".formatted(WATCH_DEVICE));
        ObjectNode stripped = (ObjectNode) lines.get(0).deepCopy();
        stripped.remove(List.of("_server", "member_id", "boot_id", "clock_mapping_id"));
        JsonNode original = MAPPER.readTree(
                RunExportFixture.syntheticRun().payloadsByS3Key().get(
                        "sensor/1023/%s/imu_watch/1.json".formatted(WATCH_DEVICE)));
        assertThat(stripped).isEqualTo(original);
    }

    @Test
    @DisplayName("보강 배치는 backfill_for가 배열이고 보강이 아니면 null이다")
    void 보강_배치는_backfill_for가_배열이고_보강이_아니면_null이다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        List<JsonNode> lines = readStream("imu_watch_%s.jsonl".formatted(WATCH_DEVICE));
        JsonNode normal = lines.get(0).get("_server");
        assertThat(normal.get("is_backfill").asBoolean()).isFalse();
        assertThat(normal.get("backfill_for").isNull()).isTrue();

        JsonNode backfilled = lines.get(lines.size() - 1).get("_server");
        assertThat(backfilled.get("is_backfill").asBoolean()).isTrue();
        assertThat(backfilled.get("backfill_for").isArray()).isTrue();
        assertThat(backfilled.get("backfill_for")).hasSize(1);
    }

    @Test
    @DisplayName("재전송 배치는 계보 여섯 필드가 모두 채워진다")
    void 재전송_배치는_계보_여섯_필드가_모두_채워진다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode resend = readStream("hr_%s.jsonl".formatted(WATCH_DEVICE)).get(0)
                .get("_server").get("resend");
        assertThat(resend.isNull()).isFalse();
        assertThat(resend.get("is_resend").asBoolean()).isTrue();
        assertThat(resend.get("original_batch_id").asText()).isEqualTo("01j8zhr0000000000000000001");
        assertThat(resend.get("original_run_id").asText()).isEqualTo(RUN_ID);
        assertThat(resend.get("original_session_id").asText()).isEqualTo("s-20260920-00");
        assertThat(resend.has("original_seq")).isTrue();
        assertThat(resend.has("resent_at_ms")).isTrue();
    }

    @Test
    @DisplayName("위치·하트비트 줄에 서버가 만든 식별자가 실리고 원문 필드가 그대로 남는다")
    void 위치와_하트비트_줄에_서버가_만든_식별자가_실리고_원문_필드가_그대로_남는다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode location = readStream("location_%s.jsonl".formatted(PHONE_DEVICE)).get(0);
        assertThat(location.get("batch_id").asText()).startsWith("loc-");
        assertThat(location.get("stream").asText()).isEqualTo("location");
        assertThat(location.get("member_id").asLong()).isEqualTo(RunExportFixture.MEMBER_ID);
        assertThat(location.get("is_mock").asBoolean()).isFalse();
        assertThat(location.get("accuracy_m").asDouble()).isEqualTo(12.5);
        assertThat(location.get("reason").asText()).isEqualTo("move");
        // 앱이 clock을 보내지 않는 스트림이라 키를 넣지 않는다(LLD-0050 5.3).
        assertThat(location.has("boot_id")).isFalse();
        assertThat(location.has("clock_mapping_id")).isFalse();

        List<JsonNode> heartbeats = readStream("heartbeat_%s.jsonl".formatted(PHONE_DEVICE));
        assertThat(heartbeats.get(0).get("batch_id").asText()).startsWith("hb-");
        // 앱이 seq를 싣지 않아 서버가 세션 안에서 0부터 부여한다.
        assertThat(heartbeats.get(0).get("seq").asLong()).isZero();
        assertThat(heartbeats.get(1).get("seq").asLong()).isEqualTo(1L);
        assertThat(heartbeats.get(2).get("seq").asLong()).isEqualTo(2L);
        assertThat(heartbeats.get(0).has("boot_id")).isFalse();
    }

    @Test
    @DisplayName("run.json에 보존 정보가 UNDECIDED로 실리고 마커 나노초가 문자열이다")
    void run_json에_보존_정보가_UNDECIDED로_실리고_마커_나노초가_문자열이다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode runJson = readJson("run.json");
        // 보존 결정이 빠진 상태를 숨기지 않고 값으로 적는다(ADR-0032 결정 3).
        assertThat(runJson.get("retention").get("data_policy").asText()).isEqualTo("UNDECIDED");
        assertThat(runJson.get("retention").get("identified_until").isNull()).isTrue();
        assertThat(runJson.get("subject_ref").asText()).isEqualTo("member-1023");
        assertThat(runJson.get("devices")).hasSize(2);
        JsonNode marker = runJson.get("markers").get(0);
        assertThat(marker.get("source_elapsed_ns").isTextual()).isTrue();
        assertThat(marker.get("source_elapsed_ns").asText()).isEqualTo("55120000001");
        assertThat(runJson.get("ecg_clock_offsets").isArray()).isTrue();
    }

    @Test
    @DisplayName("clock_mappings.json이 배열이고 나노초 구간이 문자열로 실린다")
    void clock_mappings_json이_배열이고_나노초_구간이_문자열로_실린다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode mappings = readJson("clock_mappings.json");
        assertThat(mappings.isArray()).isTrue();
        JsonNode mapping = mappings.get(0);
        assertThat(mapping.get("device_id").asText()).isEqualTo(WATCH_DEVICE);
        assertThat(mapping.get("source_clock_domain").asText()).isEqualTo("DEVICE_MONOTONIC");
        assertThat(mapping.get("target_clock_domain").asText()).isEqualTo("UTC_EPOCH_MS");
        assertThat(mapping.get("transform").asText()).isEqualTo("ANCHOR_PAIR");
        assertThat(mapping.get("evidence_method").asText()).isEqualTo("APP_REPORTED_ANCHOR");
        assertThat(mapping.get("anchors")).hasSize(1);
        assertThat(mapping.get("anchors").get(0).get("elapsed_ns").isTextual()).isTrue();
        assertThat(mapping.get("valid_from_elapsed_ns").isTextual()).isTrue();
        assertThat(mapping.get("valid_to_elapsed_ns").isTextual()).isTrue();
    }

    @Test
    @DisplayName("결측 구간이 실제 경계값과 사유로 신고되고 기대 표본 수가 배정 구간으로 계산된다")
    void 결측_구간이_실제_경계값과_사유로_신고되고_기대_표본_수가_배정_구간으로_계산된다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode quality = readJson("quality.json");
        assertThat(quality.get("run_id").asText()).isEqualTo(RUN_ID);
        assertThat(quality.get("scheduled_duration_s").asLong()).isEqualTo(180L);

        JsonNode imuStream = perStream(quality, "imu_watch");
        // 배정 180초 × 50Hz. 자료가 없다고 분모를 줄이지 않는다(ADR-0032 결정 4).
        assertThat(imuStream.get("expected_sample_count").asLong()).isEqualTo(9000L);
        // acc 180배치 × 50 + 충격 창 13배치의 자이로 50 + 보강 배치의 자이로 50.
        assertThat(imuStream.get("actual_sample_count").asLong()).isEqualTo(9700L);
        assertThat(imuStream.get("resend_count").asInt()).isZero();
        // 합성 자료의 심박 배치 둘 다 재전송이다.
        assertThat(perStream(quality, "hr").get("resend_count").asInt()).isEqualTo(1);

        // 배치가 이어져 문턱을 넘는 공백이 없다.
        assertThat(quality.get("missing_intervals")).isEmpty();
        assertThat(quality.get("unknown_duration_s").asLong()).isZero();
    }

    @Test
    @DisplayName("manifest 집계가 파일을 다시 읽어 센 값과 일치하고 없는 스트림이 사유와 함께 선언된다")
    void manifest_집계가_파일을_다시_읽어_센_값과_일치하고_없는_스트림이_사유와_함께_선언된다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode manifest = readJson("manifest.json");
        assertThat(manifest.get("format_version").asText()).isEqualTo("EXPORT_FORMAT v0.2");
        assertThat(manifest.get("server_build").asText()).isEqualTo("test-build");

        long totalRecords = 0;
        long totalSamples = 0;
        for (JsonNode file : manifest.get("files")) {
            Path path = workDir.resolve(file.get("path").asText());
            // 값을 옮겨 적지 않고 다시 세었는지 대조한다(검사기 I1·I7).
            assertThat(file.get("bytes").asLong()).isEqualTo(Files.size(path));
            assertThat(file.get("sha256").asText()).isEqualTo(sha256(Files.readAllBytes(path)));
            List<JsonNode> lines = readLines(path);
            assertThat(file.get("record_count").asLong()).isEqualTo(lines.size());
            assertThat(file.get("sample_count").asLong())
                    .isEqualTo(expectedSampleCount(file.get("stream").asText(), lines));
            totalRecords += file.get("record_count").asLong();
            totalSamples += file.get("sample_count").asLong();
        }
        assertThat(manifest.get("totals").get("record_count").asLong()).isEqualTo(totalRecords);
        assertThat(manifest.get("totals").get("sample_count").asLong()).isEqualTo(totalSamples);

        List<String> absent = new ArrayList<>();
        manifest.get("streams_absent").forEach(node -> absent.add(
                node.path("stream").asText() + "|" + node.path("device_id").asText()
                        + "|" + node.path("reason").asText()));
        // 폰에 배정됐지만 자료가 없는 스트림과 역할이 없는 스트림을 적는다(검사기 I10).
        assertThat(absent).contains(
                "imu_phone|%s|NO_DATA_IN_THIS_RUN".formatted(PHONE_DEVICE),
                "measurements||NO_DEVICE_ASSIGNED");
        // 사건이 있으면 파일이 있으니 부재를 선언하지 않는다. 선언과 실재가 어긋나면 검사기가 잡는다(I4).
        assertThat(absent).noneMatch(entry -> entry.startsWith("incidents|"));
    }

    @Test
    @DisplayName("사건이 있으면 기기 접미어 없는 incidents.jsonl에 형식서 필드가 한 줄씩 실린다")
    void 사건이_있으면_incidents_파일에_형식서_필드가_한_줄씩_실린다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        List<JsonNode> incidents = readStream("incidents.jsonl");
        assertThat(incidents).hasSize(2);

        JsonNode closed = incidents.get(0);
        assertThat(closed.get("incident_id").asText()).isEqualTo("inc-0001");
        assertThat(closed.get("run_id").asText()).isEqualTo(RUN_ID);
        assertThat(closed.get("study_id").asText()).isEqualTo("STUDY-2026");
        assertThat(closed.get("participation_id").asText()).isEqualTo("P-001");
        assertThat(closed.get("stream").asText()).isEqualTo("incidents");
        assertThat(closed.get("kind").asText()).isEqualTo("HR_ANOMALY");
        // 45초는 계약값이다(형식서 §3.7 SELF_CHECK_SEC). 검사기 L2가 ±1초로 잰다.
        assertThat(closed.get("respond_by_ms").asLong() - closed.get("opened_at_ms").asLong())
                .isEqualTo(45_000L);
        assertThat(closed.get("response").asText()).isEqualTo("OK");
        assertThat(closed.get("response_via").asText()).isEqualTo("WATCH");
        assertThat(closed.get("state").asText()).isEqualTo("OK_CLOSED");
        assertThat(closed.get("outcome").isNull()).isTrue();
        // 서버가 만든 기록이라 기기 식별자와 봉투가 없다.
        assertThat(closed.has("device_id")).isFalse();
        assertThat(closed.has("_server")).isFalse();

        JsonNode resolved = incidents.get(1);
        assertThat(resolved.get("kind").asText()).isEqualTo("FALL_SUSPECTED");
        // 이 값이 실증의 지도학습 라벨이다(형식서 §3.7).
        assertThat(resolved.get("outcome").asText()).isEqualTo("TRUE_EMERGENCY");
        assertThat(resolved.get("state").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolved_by").asLong()).isEqualTo(2048L);
        assertThat(resolved.get("emergency_called_at_ms").asLong()).isEqualTo(STARTED_AT_MS + 130_000L);
        assertThat(resolved.get("decision_id").asText()).isEqualTo("dec-01");
    }

    @Test
    @DisplayName("사건 파일은 기기 없이 manifest와 quality 집계에 사건 수 그대로 실린다")
    void 사건_파일은_기기_없이_집계에_실린다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun();

        // when
        assembler().build(run, workDir);

        // then
        JsonNode file = manifestFile("streams/incidents.jsonl");
        assertThat(file.get("stream").asText()).isEqualTo("incidents");
        assertThat(file.get("device_id").isNull()).isTrue();
        assertThat(file.get("record_count").asLong()).isEqualTo(2L);
        assertThat(file.get("sample_count").asLong()).isEqualTo(2L);
        // 봉투가 없어 시각 범위는 사건을 연 시각으로 잰다(검사기 I7).
        assertThat(file.get("first_measured_at_ms").asLong()).isEqualTo(STARTED_AT_MS + 30_000L);
        assertThat(file.get("last_measured_at_ms").asLong()).isEqualTo(STARTED_AT_MS + 100_000L);

        JsonNode quality = perStream(readJson("quality.json"), "incidents");
        // 사건은 주기로 오는 자료가 아니라 일어난 만큼만 생긴다. 기대 수가 곧 실제 건수다.
        assertThat(quality.get("device_id").isNull()).isTrue();
        assertThat(quality.get("expected_sample_count").asLong()).isEqualTo(2L);
        assertThat(quality.get("actual_sample_count").asLong()).isEqualTo(2L);
        assertThat(quality.get("coverage").asDouble()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("사건이 한 건도 없으면 파일을 만들지 않고 자료 없음으로 신고한다")
    void 사건이_없으면_파일을_만들지_않고_자료_없음으로_신고한다() throws IOException {
        // given
        CollectionRun run = givenSyntheticRun(List.of());

        // when
        assembler().build(run, workDir);

        // then
        // 「파일 없음 + 부재 선언」과 「파일 있음 + 0건」은 다른 명제다(형식서 §1, 검사기 I5).
        assertThat(streamNames()).doesNotContain("incidents.jsonl");
        List<String> absent = new ArrayList<>();
        readJson("manifest.json").get("streams_absent").forEach(node -> absent.add(
                node.path("stream").asText() + "|" + node.path("reason").asText()));
        assertThat(absent).contains("incidents|NO_DATA_IN_THIS_RUN");
    }

    // ── 합성 자료 ──────────────────────────────────────────────────

    private CollectionRun givenSyntheticRun() {
        return givenSyntheticRun(syntheticIncidents());
    }

    private CollectionRun givenSyntheticRun(List<Incident> incidents) {
        CollectionRun run = RunExportFixture.closedRun();
        RunExportFixture.SyntheticRun synthetic = RunExportFixture.syntheticRun();
        given(runDeviceAssignmentRepository.findByRun_IdOrderByIdAsc(run.getId()))
                .willReturn(List.of(
                        RunExportFixture.assignment(WATCH_DEVICE, "watch"),
                        RunExportFixture.assignment(PHONE_DEVICE, "phone")));
        given(runMarkerRepository.findByRun_IdOrderByTsMsAsc(run.getId()))
                .willReturn(List.of(RunExportFixture.marker()));

        given(sensorBatchRepository.findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(RUN_ID, "imu_watch"))
                .willReturn(synthetic.imuBatches());
        given(sensorBatchRepository.findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(RUN_ID, "imu_phone"))
                .willReturn(List.of());
        given(sensorBatchRepository.findByRunIdAndStreamOrderByMeasuredAtStartMsAscSeqAsc(RUN_ID, "hr"))
                .willReturn(synthetic.hrBatches());
        given(sensorBatchRepository.findByRunIdOrderByMeasuredAtStartMsAsc(RUN_ID))
                .willReturn(synthetic.imuBatches());
        given(clockMappingRepository.findByClockMappingIdIn(any()))
                .willReturn(List.of(RunExportFixture.clockMapping(
                        synthetic.observedMinElapsedNs(), synthetic.observedMaxElapsedNs())));

        given(locationFixRepository.findByRunIdOrderByTsMsAscSeqAsc(RUN_ID))
                .willReturn(synthetic.locationFixes());
        given(deviceHeartbeatRepository.findByRunIdOrderByTsMsAsc(RUN_ID))
                .willReturn(synthetic.heartbeats());
        given(decisionRecordRepository.findByRunIdOrderByDecisionAtMsAsc(RUN_ID))
                .willReturn(syntheticDecisions());
        given(incidentRepository.findByRunIdOrderByOpenedAtMsAsc(RUN_ID)).willReturn(incidents);
        given(s3Service.downloadBytes(any())).willAnswer(invocation ->
                synthetic.payloadsByS3Key().get(invocation.<String>getArgument(0))
                        .getBytes(StandardCharsets.UTF_8));
        return run;
    }

    private List<DecisionRecord> syntheticDecisions() {
        DecisionRecord alert = DecisionRecord.builder()
                .decisionId("dec-01")
                .memberId(RunExportFixture.MEMBER_ID)
                .runId(RUN_ID)
                .streamIdsUsed("[\"01j8zimu000000000000000001\"]")
                .decisionAtMs(STARTED_AT_MS + 10_500L)
                .decisionOutput("ALERT")
                .deciderId("fall-ai")
                .deciderVersion("2026.09")
                .inputCutoffMs(STARTED_AT_MS + 10_400L)
                .featureSupportEndMs(STARTED_AT_MS + 10_300L)
                .modelAvailableAtServerMaxMs(STARTED_AT_MS + 10_350L)
                .windowStartMs(STARTED_AT_MS)
                .windowEndMs(STARTED_AT_MS + 10_000L)
                .severity("HIGH")
                .triggerPath("IMPACT")
                .triggerBatchId("01j8zimu000000000000000001")
                .build();
        ReflectionTestUtils.setField(alert, "alertId", "alert-01");
        ReflectionTestUtils.setField(alert, "alertAtMs", STARTED_AT_MS + 10_550L);
        ReflectionTestUtils.setField(alert, "alertDelivered", true);

        DecisionRecord abstain = DecisionRecord.builder()
                .decisionId("dec-02")
                .memberId(RunExportFixture.MEMBER_ID)
                .runId(RUN_ID)
                .streamIdsUsed("[]")
                .decisionAtMs(STARTED_AT_MS + 20_000L)
                .decisionOutput("ABSTAIN_INSUFFICIENT_INPUT")
                .deciderId("fall-ai")
                .deciderVersion("2026.09")
                .inputCutoffMs(STARTED_AT_MS + 19_900L)
                .featureSupportEndMs(STARTED_AT_MS + 19_800L)
                .modelAvailableAtServerMaxMs(STARTED_AT_MS + 19_850L)
                .windowStartMs(STARTED_AT_MS + 10_000L)
                .windowEndMs(STARTED_AT_MS + 20_000L)
                .triggerPath("IMPACT")
                .triggerBatchId("01j8zimu000000000000000002")
                .build();

        DecisionRecord heartAlert = DecisionRecord.builder()
                .decisionId("dec-03")
                .memberId(RunExportFixture.MEMBER_ID)
                .runId(RUN_ID)
                .streamIdsUsed("[\"01j8zhr0000000000000001010\"]")
                .decisionAtMs(STARTED_AT_MS + 30_400L)
                .decisionOutput("ALERT")
                .deciderId("widyu-ai-hr")
                .deciderVersion("ver7")
                .inputCutoffMs(STARTED_AT_MS + 30_300L)
                .featureSupportEndMs(STARTED_AT_MS + 30_000L)
                .modelAvailableAtServerMaxMs(STARTED_AT_MS + 30_300L)
                .windowStartMs(STARTED_AT_MS + 30_000L)
                .windowEndMs(STARTED_AT_MS + 30_000L)
                .severity("EMERGENCY")
                .triggerBatchId("01j8zhr0000000000000001010")
                .hrBpm(185)
                .hrMeasuredAtMs(STARTED_AT_MS + 30_000L)
                .hrAccuracy("HIGH")
                .reason("연속 3회 임계 초과")
                .build();

        return List.of(alert, abstain, heartAlert);
    }

    /** 본인이 답하고 닫힌 사건 하나, 무응답 뒤 보호자가 위급으로 판정한 사건 하나(LLD-0054 7절). */
    private List<Incident> syntheticIncidents() {
        Incident closed = Incident.builder()
                .incidentRef("inc-0001")
                .memberId(RunExportFixture.MEMBER_ID)
                .runId(RUN_ID)
                .decisionId("dec-03")
                .kind(IncidentKind.HR_ANOMALY)
                .level("EMERGENCY")
                .openedAtMs(STARTED_AT_MS + 30_000L)
                .respondByMs(STARTED_AT_MS + 75_000L)
                .build();
        closed.markChecking();
        // 응답은 조건부 UPDATE가 쓰므로 저장된 행의 모습을 그대로 만든다.
        ReflectionTestUtils.setField(closed, "response", IncidentResponseValue.OK);
        ReflectionTestUtils.setField(closed, "responseVia", ResponseVia.WATCH);
        ReflectionTestUtils.setField(closed, "respondedAtMs", STARTED_AT_MS + 42_000L);
        ReflectionTestUtils.setField(closed, "state", IncidentState.OK_CLOSED);

        Incident resolved = Incident.builder()
                .incidentRef("inc-0002")
                .memberId(RunExportFixture.MEMBER_ID)
                .runId(RUN_ID)
                .decisionId("dec-01")
                .kind(IncidentKind.FALL_SUSPECTED)
                .level("HIGH")
                .openedAtMs(STARTED_AT_MS + 100_000L)
                .respondByMs(STARTED_AT_MS + 145_000L)
                .build();
        resolved.markChecking();
        // 사후 판정도 조건부 UPDATE가 쓰므로 저장된 행의 모습을 그대로 만든다.
        ReflectionTestUtils.setField(resolved, "state", IncidentState.RESOLVED);
        ReflectionTestUtils.setField(resolved, "outcome", IncidentOutcome.TRUE_EMERGENCY);
        ReflectionTestUtils.setField(resolved, "resolvedBy", 2048L);
        ReflectionTestUtils.setField(resolved, "resolvedAtMs", STARTED_AT_MS + 160_000L);
        ReflectionTestUtils.setField(resolved, "emergencyCalledAtMs", STARTED_AT_MS + 130_000L);

        return List.of(closed, resolved);
    }

    private RunExportAssembler assembler() {
        return new RunExportAssembler(
                sensorBatchRepository, locationFixRepository, deviceHeartbeatRepository, decisionRecordRepository,
                incidentRepository, clockMappingRepository, runDeviceAssignmentRepository, runMarkerRepository,
                s3Service, MAPPER, RunExportFixture.properties());
    }

    // ── 도우미 ─────────────────────────────────────────────────────

    private List<String> streamNames() throws IOException {
        try (var files = Files.list(workDir.resolve("streams"))) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private JsonNode readJson(String name) throws IOException {
        return MAPPER.readTree(Files.readString(workDir.resolve(name), StandardCharsets.UTF_8));
    }

    private List<JsonNode> readStream(String name) throws IOException {
        return readLines(workDir.resolve("streams").resolve(name));
    }

    private List<JsonNode> readLines(Path path) throws IOException {
        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                lines.add(MAPPER.readTree(line));
            }
        }
        return lines;
    }

    private JsonNode manifestFile(String path) throws IOException {
        for (JsonNode file : readJson("manifest.json").get("files")) {
            if (path.equals(file.get("path").asText())) {
                return file;
            }
        }
        throw new IllegalStateException("manifest.files에 %s가 없습니다.".formatted(path));
    }

    private JsonNode perStream(JsonNode quality, String stream) {
        for (JsonNode node : quality.get("per_stream")) {
            if (stream.equals(node.get("stream").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("per_stream에 %s가 없습니다.".formatted(stream));
    }

    private long expectedSampleCount(String stream, List<JsonNode> lines) {
        long total = 0;
        for (JsonNode line : lines) {
            if (stream.startsWith("imu_")) {
                total += axis(line.get("acc")) + axis(line.get("gyro"));
                continue;
            }
            if ("hr".equals(stream)) {
                total += line.get("samples").size();
                continue;
            }
            total++;
        }
        return total;
    }

    private long axis(JsonNode axis) {
        if (axis == null || axis.isNull()) {
            return 0;
        }
        return axis.get("n").asLong();
    }

    private String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** 검사기(`check_export.py`)에 넣을 zip을 남긴다. {@code -Dwidyu.export.keep=true}일 때만 만든다. */
    private void keepZipIfRequested() throws IOException {
        if (!Boolean.parseBoolean(System.getProperty("widyu.export.keep", "false"))) {
            return;
        }
        Path outDir = Path.of("build", "export-samples");
        Files.createDirectories(outDir);
        Path zip = outDir.resolve("run_%s.zip".formatted(RUN_ID));
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zipOut = new ZipOutputStream(out)) {
            List<Path> entries;
            try (var walk = Files.walk(workDir)) {
                entries = walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString)).toList();
            }
            for (Path entry : entries) {
                zipOut.putNextEntry(new ZipEntry(workDir.relativize(entry).toString()));
                Files.copy(entry, zipOut);
                zipOut.closeEntry();
            }
        }
        System.out.println("[export-sample] " + zip.toAbsolutePath());
    }
}
