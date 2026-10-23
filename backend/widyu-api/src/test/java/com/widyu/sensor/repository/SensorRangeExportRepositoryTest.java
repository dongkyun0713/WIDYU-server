package com.widyu.sensor.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.querydsl.jpa.impl.JPAQueryFactory;
import com.widyu.global.config.JpaAuditingConfig;
import com.widyu.member.Member;
import com.widyu.member.MemberType;
import com.widyu.sensor.SensorBatch;
import com.widyu.sensor.SensorRangeExport;
import com.widyu.sensor.SensorRangeExportStatus;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@Import(JpaAuditingConfig.class)
@DisplayName("센서 시간 범위 내보내기 영속성")
class SensorRangeExportRepositoryTest {

    @Autowired private SensorRangeExportRepository exportRepository;
    @Autowired private SensorBatchRepository batchRepository;
    @Autowired private TestEntityManager entityManager;
    @MockBean private JPAQueryFactory jpaQueryFactory;

    @Test
    @DisplayName("만료된 시도의 완료와 실패가 재선점된 작업의 상태를 바꾸지 못한다")
    void 만료된_시도는_재선점된_작업의_상태를_바꾸지_못한다() {
        // given
        Member member = entityManager.persistAndFlush(Member.createMember(MemberType.SENIOR, "테스트", null));
        SensorRangeExport export = exportRepository.saveAndFlush(SensorRangeExport.builder()
                .exportId("sexp-persistence")
                .memberId(member.getId())
                .startAtMs(1000L)
                .endAtMs(2000L)
                .status(SensorRangeExportStatus.QUEUED)
                .requestedAtMs(3000L)
                .build());

        // when
        assertThat(exportRepository.claim(export.getId(), 4000L)).isEqualTo(1);
        assertThat(exportRepository.findById(export.getId()).orElseThrow().getStartedAtMs()).isEqualTo(4000L);
        assertThat(exportRepository.requeueExpiredRunning(4001L)).isEqualTo(1);
        assertThat(exportRepository.claim(export.getId(), 5000L)).isEqualTo(1);
        int staleFinish = exportRepository.finishIfRunning(
                export.getId(), 4000L, "old.zip", 10L, "a".repeat(64), 6000L);
        int staleFail = exportRepository.failIfRunning(export.getId(), 4000L, "IOException", 6000L);
        int currentFinish = exportRepository.finishIfRunning(
                export.getId(), 5000L, "new.zip", 20L, "b".repeat(64), 7000L);

        // then
        SensorRangeExport saved = exportRepository.findById(export.getId()).orElseThrow();
        assertThat(staleFinish).isZero();
        assertThat(staleFail).isZero();
        assertThat(currentFinish).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(SensorRangeExportStatus.DONE);
        assertThat(saved.getS3Key()).isEqualTo("new.zip");
    }

    @Test
    @DisplayName("회원과 스트림과 배치 겹침 조건을 모두 적용해 경계 배치를 읽는다")
    void 회원과_스트림과_배치_겹침_조건을_적용한다() {
        // given
        Member member = entityManager.persistAndFlush(Member.createMember(MemberType.SENIOR, "회원1", null));
        Member other = entityManager.persistAndFlush(Member.createMember(MemberType.SENIOR, "회원2", null));
        SensorBatch overlap = entityManager.persistAndFlush(batch(member, "imu_watch", "batch-a", 900L, 1001L));
        entityManager.persistAndFlush(batch(member, "imu_watch", "batch-b", 1002L, 1003L));
        entityManager.persistAndFlush(batch(member, "imu_watch", "batch-c", 999L, 999L));
        entityManager.persistAndFlush(batch(member, "hr", "batch-d", 1000L, 1002L));
        entityManager.persistAndFlush(batch(other, "imu_watch", "batch-e", 1000L, 1002L));
        entityManager.clear();

        // when
        List<SensorBatch> page = batchRepository.findRangePage(
                member.getId(), "imu_watch", 1000L, 1002L, -1L, 0L, PageRequest.of(0, 1));
        List<SensorBatch> next = batchRepository.findRangePage(
                member.getId(), "imu_watch", 1000L, 1002L,
                page.get(0).getMeasuredAtStartMs(), page.get(0).getId(), PageRequest.of(0, 1));

        // then
        assertThat(page).extracting(SensorBatch::getId).containsExactly(overlap.getId());
        assertThat(next).isEmpty();
    }

    private SensorBatch batch(Member member, String stream, String batchId, long startMs, long endMs) {
        return SensorBatch.builder()
                .batchId(batchId).member(member).stream(stream).source("watch")
                .deviceId("watch-a").sessionId("session-a").seq(1L)
                .bootId("boot-a").clockMappingId("map-a")
                .anchorElapsedNs(0L).anchorEpochMs(0L).uncertaintyMs(0.0)
                .measuredAtStartMs(startMs).measuredAtEndMs(endMs)
                .serverReceivedAtMs(endMs).acceptedAtMs(endMs).persistedAtMs(endMs)
                .modelAvailableAtServerMs(endMs)
                .onBody(true).qualityStatus("OK")
                .gyroBackfill(false).isResend(false)
                .s3Key("test/" + batchId).byteSize(1).payloadSha256("a".repeat(64))
                .configMismatch(false)
                .build();
    }
}
