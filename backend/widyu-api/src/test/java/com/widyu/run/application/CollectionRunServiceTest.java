package com.widyu.run.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import com.widyu.global.error.BusinessException;
import com.widyu.global.error.ErrorCode;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.member.repository.MemberRepository;
import com.widyu.run.CollectionRun;
import com.widyu.run.CollectionRunStatus;
import com.widyu.run.RunDeviceAssignment;
import com.widyu.run.RunMarker;
import com.widyu.run.dto.request.CollectionRunCloseRequest;
import com.widyu.run.dto.request.CollectionRunOpenRequest;
import com.widyu.run.dto.request.DeviceAssignRequest;
import com.widyu.run.dto.request.RunMarkerRequest;
import com.widyu.run.dto.response.CollectionRunResponse;
import com.widyu.run.repository.CollectionRunRepository;
import com.widyu.run.repository.RunDeviceAssignmentRepository;
import com.widyu.run.repository.RunMarkerRepository;
import com.widyu.sensor.application.ClockMappingService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@DisplayName("CollectionRunService 단위 테스트")
class CollectionRunServiceTest {

    private static final Long MEMBER_ID = 1023L;
    private static final String RUN_ID = "run-0f3a";
    private static final String DEVICE_ID = "gw-3f2a";
    private static final long STARTED_AT_MS = 1_760_000_000_000L;

    @Mock private CollectionRunRepository collectionRunRepository;
    @Mock private CollectionRunOpenCommandService collectionRunOpenCommandService;
    @Mock private CollectionRunConflictLookupService collectionRunConflictLookupService;
    @Mock private RunDeviceAssignmentRepository runDeviceAssignmentRepository;
    @Mock private RunDeviceAssignmentInsertService runDeviceAssignmentInsertService;
    @Mock private RunMarkerRepository runMarkerRepository;
    @Mock private RunMarkerInsertService runMarkerInsertService;
    @Mock private MemberRepository memberRepository;
    @Mock private ClockMappingService clockMappingService;
    @Mock private com.widyu.admin.application.AdminAuditLogService adminAuditLogService;

    @InjectMocks private CollectionRunService collectionRunService;

    @Test
    @DisplayName("회차를 열면 서버가 발급한 run_id와 기기 배정이 함께 저장된다")
    void 회차를_열면_서버가_발급한_run_id와_기기_배정이_함께_저장된다() {
        // given
        Member member = member();
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(false);
        given(collectionRunOpenCommandService.open(any(CollectionRun.class), any())).willAnswer(i -> i.getArgument(0));
        given(runDeviceAssignmentRepository.existsByDeviceIdAndUnassignedAtMsIsNullAndRun_Status(
                DEVICE_ID, CollectionRunStatus.OPEN)).willReturn(false);
        CollectionRunOpenRequest request = CollectionRunOpenRequest.of(
                MEMBER_ID, "STUDY-2026", "P-001", "PROTO-A", "v1", null, STARTED_AT_MS, null,
                List.of(DeviceAssignRequest.of(DEVICE_ID, "watch", "LEFT_WRIST", null)));

        // when
        collectionRunService.open(request);

        // then
        ArgumentCaptor<CollectionRun> savedRun = ArgumentCaptor.forClass(CollectionRun.class);
        then(collectionRunOpenCommandService).should().open(savedRun.capture(), any());
        CollectionRun run = savedRun.getValue();
        assertThat(run.getRunId()).startsWith("run-").hasSize(36);
        assertThat(run.getStatus()).isEqualTo(CollectionRunStatus.OPEN);
        // 수집 모드 기본값은 research다.
        assertThat(run.getCollectionMode()).isEqualTo("research");
        assertThat(run.getStartedAtMs()).isEqualTo(STARTED_AT_MS);
        assertThat(run.getStudyId()).isEqualTo("STUDY-2026");

        ArgumentCaptor<List<RunDeviceAssignment>> savedAssignments = ArgumentCaptor.forClass(List.class);
        then(collectionRunOpenCommandService).should().open(any(), savedAssignments.capture());
        RunDeviceAssignment assignment = savedAssignments.getValue().get(0);
        assertThat(assignment.getAssignmentId()).startsWith("asg-").hasSize(36);
        assertThat(assignment.getDeviceId()).isEqualTo(DEVICE_ID);
        assertThat(assignment.getWearSite()).isEqualTo("LEFT_WRIST");
        // 배정 시각을 주지 않으면 회차 시작 시각을 쓴다.
        assertThat(assignment.getAssignedAtMs()).isEqualTo(STARTED_AT_MS);
        assertThat(assignment.isAssigned()).isTrue();
    }

    @Test
    @DisplayName("회원에게 이미 열린 회차가 있으면 예외가 발생한다")
    void 회원에게_이미_열린_회차가_있으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> collectionRunService.open(openRequest(null, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_ALREADY_OPEN);
        then(collectionRunOpenCommandService).should(never()).open(any(), any());
    }

    @Test
    @DisplayName("기기가 다른 열린 회차에 배정돼 있으면 예외가 발생한다")
    void 기기가_다른_열린_회차에_배정돼_있으면_예외가_발생한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(false);
        given(runDeviceAssignmentRepository.existsByDeviceIdAndUnassignedAtMsIsNullAndRun_Status(
                DEVICE_ID, CollectionRunStatus.OPEN)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> collectionRunService.open(openRequest(null,
                List.of(DeviceAssignRequest.of(DEVICE_ID, "watch", null, null)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_DEVICE_ALREADY_ASSIGNED);
        then(collectionRunOpenCommandService).should(never()).open(any(), any());
    }

    @Test
    @DisplayName("초기 기기 배정 저장이 충돌하면 회차 전체를 롤백하고 409을 반환한다")
    void 초기_기기_배정_저장이_충돌하면_회차_전체를_롤백하고_409을_반환한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(false);
        willThrow(new DataIntegrityViolationException("device_id"))
                .given(collectionRunOpenCommandService).open(any(CollectionRun.class), any());
        given(collectionRunConflictLookupService.hasOpenRun(MEMBER_ID)).willReturn(false);
        given(collectionRunConflictLookupService.hasActiveDevice(DEVICE_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> collectionRunService.open(openRequest(
                null, List.of(DeviceAssignRequest.of(DEVICE_ID, "watch", null, null)))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_DEVICE_ALREADY_ASSIGNED);
    }

    @Test
    @DisplayName("보존 정책이 RETAIN인데 날짜가 빠지면 예외가 발생한다")
    void 보존_정책이_RETAIN인데_날짜가_빠지면_예외가_발생한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(false);
        CollectionRunOpenRequest.RetentionRequest retention =
                CollectionRunOpenRequest.RetentionRequest.of(
                        "RETAIN", LocalDate.of(2027, 3, 31), null, LocalDate.of(2029, 3, 31));

        // when & then
        assertThatThrownBy(() -> collectionRunService.open(openRequest(retention, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_RETENTION_INVALID);
        then(collectionRunRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("보존 날짜 순서가 뒤바뀌면 예외가 발생한다")
    void 보존_날짜_순서가_뒤바뀌면_예외가_발생한다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member()));
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(false);
        CollectionRunOpenRequest.RetentionRequest retention =
                CollectionRunOpenRequest.RetentionRequest.of("RETAIN",
                        LocalDate.of(2029, 3, 31), LocalDate.of(2027, 3, 31), LocalDate.of(2029, 3, 31));

        // when & then
        assertThatThrownBy(() -> collectionRunService.open(openRequest(retention, List.of())))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_RETENTION_INVALID);
    }

    @Test
    @DisplayName("회차를 닫으면 미해제 배정이 종료 시각으로 함께 해제된다")
    void 회차를_닫으면_미해제_배정이_종료_시각으로_함께_해제된다() {
        // given
        CollectionRun run = openRun();
        RunDeviceAssignment assignment = assignment(run, STARTED_AT_MS);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runDeviceAssignmentRepository.findByRun_IdAndUnassignedAtMsIsNull(run.getId()))
                .willReturn(List.of(assignment));
        givenEmptyRunContents(run);
        long endedAtMs = STARTED_AT_MS + 3_600_000L;

        // when
        CollectionRunResponse response = collectionRunService.close(
                RUN_ID, CollectionRunCloseRequest.of(endedAtMs, "정상 종료", null));

        // then
        assertThat(run.getStatus()).isEqualTo(CollectionRunStatus.CLOSED);
        assertThat(run.getEndedAtMs()).isEqualTo(endedAtMs);
        assertThat(run.getQualityNotes()).isEqualTo("정상 종료");
        assertThat(assignment.getUnassignedAtMs()).isEqualTo(endedAtMs);
        assertThat(assignment.isAssigned()).isFalse();
        assertThat(response.status()).isEqualTo(CollectionRunStatus.CLOSED);
    }

    @Test
    @DisplayName("이미 닫힌 회차를 닫으면 예외가 발생한다")
    void 이미_닫힌_회차를_닫으면_예외가_발생한다() {
        // given
        CollectionRun run = openRun();
        run.close(STARTED_AT_MS + 1000L, null, null);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));

        // when & then
        assertThatThrownBy(() -> collectionRunService.close(RUN_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_NOT_OPEN);
    }

    @Test
    @DisplayName("같은 마커를 같은 내용으로 다시 등록하면 저장하지 않고 기존 회차를 반환한다")
    void 같은_마커를_같은_내용으로_다시_등록하면_저장하지_않고_기존_회차를_반환한다() {
        // given
        CollectionRun run = openRun();
        RunMarkerRequest request = markerRequest(STARTED_AT_MS + 12_000L);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runMarkerRepository.findByMarkerId(request.markerId()))
                .willReturn(Optional.of(markerOf(run, request)));
        givenEmptyRunContents(run);

        // when
        CollectionRunResponse response = collectionRunService.registerMarker(RUN_ID, request);

        // then
        assertThat(response.runId()).isEqualTo(RUN_ID);
        then(runMarkerInsertService).should(never()).insert(any());
        then(clockMappingService).should().register(
                any(), eq(request.sourceDeviceId()), anyLong(), anyLong());
    }

    @Test
    @DisplayName("같은 마커 ID로 다른 내용을 등록하면 예외가 발생한다")
    void 같은_마커_ID로_다른_내용을_등록하면_예외가_발생한다() {
        // given
        CollectionRun run = openRun();
        RunMarkerRequest registered = markerRequest(STARTED_AT_MS + 12_000L);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runMarkerRepository.findByMarkerId(registered.markerId()))
                .willReturn(Optional.of(markerOf(run, registered)));

        // when & then
        assertThatThrownBy(() ->
                collectionRunService.registerMarker(RUN_ID, markerRequest(STARTED_AT_MS + 99_000L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_MARKER_CONFLICT);
        then(runMarkerInsertService).should(never()).insert(any());
    }

    @Test
    @DisplayName("마커를 등록하면 누른 기기의 시계 매핑도 함께 등록된다")
    void 마커를_등록하면_누른_기기의_시계_매핑도_함께_등록된다() {
        // given
        CollectionRun run = openRun();
        RunMarkerRequest request = markerRequest(STARTED_AT_MS + 12_000L);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runMarkerRepository.findByMarkerId(request.markerId())).willReturn(Optional.empty());
        givenEmptyRunContents(run);

        // when
        collectionRunService.registerMarker(RUN_ID, request);

        // then
        then(clockMappingService).should().register(
                any(), eq("op-1"), eq(55_120_000_001L), eq(55_120_000_001L));
        ArgumentCaptor<RunMarker> saved = ArgumentCaptor.forClass(RunMarker.class);
        then(runMarkerInsertService).should().insert(saved.capture());
        assertThat(saved.getValue().getMarkerId()).isEqualTo("01j8zmark0000000000000001");
        assertThat(saved.getValue().getLabel()).isEqualTo("sit_to_stand");
        assertThat(saved.getValue().getClockMappingId()).isEqualTo("cm-op-1");
    }

    @Test
    @DisplayName("동시에 같은 마커를 등록해도 같은 내용이면 기존 회차를 반환한다")
    void 동시에_같은_마커를_등록해도_같은_내용이면_기존_회차를_반환한다() {
        // given
        CollectionRun run = openRun();
        RunMarkerRequest request = markerRequest(STARTED_AT_MS + 12_000L);
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runMarkerRepository.findByMarkerId(request.markerId()))
                .willReturn(Optional.empty(), Optional.of(markerOf(run, request)));
        givenEmptyRunContents(run);
        willThrow(new DataIntegrityViolationException("marker_id"))
                .given(runMarkerInsertService).insert(any(RunMarker.class));

        // when
        CollectionRunResponse response = collectionRunService.registerMarker(RUN_ID, request);

        // then
        assertThat(response.runId()).isEqualTo(RUN_ID);
        then(runMarkerInsertService).should().insert(any(RunMarker.class));
    }

    @Test
    @DisplayName("마커 시각이 회차 시작보다 앞서면 예외가 발생한다")
    void 마커_시각이_회차_시작보다_앞서면_예외가_발생한다() {
        // given
        CollectionRun run = openRun();
        given(collectionRunRepository.findByRunId(RUN_ID)).willReturn(Optional.of(run));
        given(runMarkerRepository.findByMarkerId(anyString())).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() ->
                collectionRunService.registerMarker(RUN_ID, markerRequest(STARTED_AT_MS - 1L)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RUN_MARKER_OUT_OF_RANGE);
        then(runMarkerInsertService).should(never()).insert(any());
        then(clockMappingService).should(never()).register(any(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("배정 구간 안의 시각이면 그 회차를 찾아 반환한다")
    void 배정_구간_안의_시각이면_그_회차를_찾아_반환한다() {
        // given
        CollectionRun run = openRun();
        long duringMs = STARTED_AT_MS + 60_000L;
        given(runDeviceAssignmentRepository.findOpenRunsByDeviceAt(MEMBER_ID, DEVICE_ID, duringMs))
                .willReturn(List.of(run));

        // when
        Optional<CollectionRun> resolved =
                collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, duringMs);

        // then
        assertThat(resolved).containsSame(run);
    }

    @Test
    @DisplayName("배정 구간 밖의 시각이면 회차를 찾지 못한다")
    void 배정_구간_밖의_시각이면_회차를_찾지_못한다() {
        // given
        long beforeAssignedMs = STARTED_AT_MS - 60_000L;
        given(runDeviceAssignmentRepository.findOpenRunsByDeviceAt(
                MEMBER_ID, DEVICE_ID, beforeAssignedMs)).willReturn(List.of());

        // when
        Optional<CollectionRun> resolved =
                collectionRunService.resolveRun(MEMBER_ID, DEVICE_ID, beforeAssignedMs);

        // then
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("회원에게 열린 회차가 있는지 조회하면 저장소 결과를 그대로 반환한다")
    void 회원에게_열린_회차가_있는지_조회하면_저장소_결과를_그대로_반환한다() {
        // given
        given(collectionRunRepository.existsByMemberIdAndStatus(MEMBER_ID, CollectionRunStatus.OPEN))
                .willReturn(true);

        // when & then
        assertThat(collectionRunService.hasOpenRun(MEMBER_ID)).isTrue();
    }

    private void givenEmptyRunContents(CollectionRun run) {
        given(runDeviceAssignmentRepository.findByRun_IdOrderByIdAsc(run.getId())).willReturn(List.of());
        given(runMarkerRepository.findByRun_IdOrderByTsMsAsc(run.getId())).willReturn(List.of());
    }

    private Member member() {
        Member member = Member.createMember(MemberType.SENIOR, "시니어", "01012345678");
        org.springframework.test.util.ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        return member;
    }

    private CollectionRunOpenRequest openRequest(
            CollectionRunOpenRequest.RetentionRequest retention, List<DeviceAssignRequest> devices) {
        return CollectionRunOpenRequest.of(
                MEMBER_ID, null, null, null, null, null, STARTED_AT_MS, retention, devices);
    }

    private CollectionRun openRun() {
        CollectionRun run = CollectionRun.builder()
                .runId(RUN_ID)
                .member(member())
                .studyId("STUDY-2026")
                .participationId("P-001")
                .collectionMode("research")
                .startedAtMs(STARTED_AT_MS)
                .status(CollectionRunStatus.OPEN)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", 7L);
        return run;
    }

    private RunDeviceAssignment assignment(CollectionRun run, long assignedAtMs) {
        return RunDeviceAssignment.builder()
                .assignmentId("asg-1")
                .run(run)
                .deviceId(DEVICE_ID)
                .role("watch")
                .assignedAtMs(assignedAtMs)
                .build();
    }

    private RunMarkerRequest markerRequest(long tsMs) {
        return RunMarkerRequest.of(
                "01j8zmark0000000000000001",
                "TASK_START",
                "sit_to_stand",
                "55120000001",
                tsMs,
                "OPERATOR_APP",
                "op-1",
                RunMarkerRequest.MarkerClockRequest.of(
                        "b7c1", "cm-op-1", "55000000001", 1_760_000_000_000L, 2.0));
    }

    private RunMarker markerOf(CollectionRun run, RunMarkerRequest request) {
        return RunMarker.builder()
                .markerId(request.markerId())
                .run(run)
                .kind(request.kind())
                .label(request.label())
                .sourceElapsedNs(Long.parseLong(request.sourceElapsedNs()))
                .tsMs(request.tsMs())
                .source(request.source())
                .sourceDeviceId(request.sourceDeviceId())
                .clockMappingId(request.clock().clockMappingId())
                .build();
    }
}
