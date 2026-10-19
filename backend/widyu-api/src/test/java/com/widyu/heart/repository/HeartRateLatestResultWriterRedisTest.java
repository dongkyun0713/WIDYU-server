package com.widyu.heart.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.widyu.heart.HeartRateResult;
import com.widyu.heart.HeartRateStatus;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 최신 심박 조건부 쓰기를 실제 Redis에서 검증한다(LLD-0090 AC1·AC2, 6·8절).
 *
 * <p>비교·갱신·TTL 재설정이 Lua 스크립트 안에서 일어나 대역으로는 검증할 수 없다. {@code HEART_REDIS_PORT}로 지정한
 * 버리는 Redis가 있을 때만 실행하고, 없으면 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = HeartRateRedisFixture.PORT_ENV, matches = "\\d+")
@DisplayName("최신 심박 조건부 쓰기 (실제 Redis)")
class HeartRateLatestResultWriterRedisTest {

    private static final long DAY_SECONDS = 86_400L;
    private static final long SHORT_TTL_SECONDS = 100L;
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 19, 9, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 19, 9, 0, 1, 500_000_000);

    @Test
    @DisplayName("저장된 값이 없으면 새 값을 저장하고 findByMemberId로 같은 필드를 읽는다")
    void 저장된_값이_없으면_새_값을_저장하고_findByMemberId로_같은_필드를_읽는다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_001L;

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.CAUTION, 120, T2));

            // then
            assertThat(updated).isTrue();
            HeartRateResult read = redis.repository().findByMemberId(memberId).orElseThrow();
            assertThat(read.getMemberId()).isEqualTo(memberId);
            assertThat(read.getStatus()).isEqualTo(HeartRateStatus.CAUTION);
            assertThat(read.getHeartRate()).isEqualTo(120);
            assertThat(read.getMeasuredAt()).isEqualTo(T2);
            assertThat(redis.ttl(HeartRateRedisFixture.key(memberId))).isBetween(DAY_SECONDS - 5, DAY_SECONDS);
            assertThat(redis.isMember("heart_rate_result", memberId)).isTrue();
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("더 늦은 측정을 쓰면 값을 갱신하고 TTL을 24시간으로 다시 건다")
    void 더_늦은_측정을_쓰면_값을_갱신하고_TTL을_24시간으로_다시_건다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_002L;
            redis.writer().saveIfNewer(HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 70, T1));
            redis.expire(HeartRateRedisFixture.key(memberId), SHORT_TTL_SECONDS);

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 180, T2));

            // then
            assertThat(updated).isTrue();
            HeartRateResult read = redis.repository().findByMemberId(memberId).orElseThrow();
            assertThat(read.getMeasuredAt()).isEqualTo(T2);
            assertThat(read.getStatus()).isEqualTo(HeartRateStatus.EMERGENCY);
            assertThat(read.getHeartRate()).isEqualTo(180);
            assertThat(redis.ttl(HeartRateRedisFixture.key(memberId))).isBetween(DAY_SECONDS - 5, DAY_SECONDS);
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("더 이른 측정을 쓰면 갱신하지 않고 기존 값과 TTL을 유지한다")
    void 더_이른_측정을_쓰면_갱신하지_않고_기존_값과_TTL을_유지한다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_003L;
            redis.writer().saveIfNewer(HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 72, T2));
            redis.expire(HeartRateRedisFixture.key(memberId), SHORT_TTL_SECONDS);

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 180, T1));

            // then
            assertThat(updated).isFalse();
            HeartRateResult read = redis.repository().findByMemberId(memberId).orElseThrow();
            assertThat(read.getMeasuredAt()).isEqualTo(T2);
            assertThat(read.getStatus()).isEqualTo(HeartRateStatus.NORMAL);
            assertThat(read.getHeartRate()).isEqualTo(72);
            assertThat(redis.ttl(HeartRateRedisFixture.key(memberId))).isBetween(1L, SHORT_TTL_SECONDS);
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("같은 측정 시각을 다시 쓰면 갱신하지 않고 기존 값과 TTL을 유지한다")
    void 같은_측정_시각을_다시_쓰면_갱신하지_않고_기존_값과_TTL을_유지한다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_004L;
            redis.writer().saveIfNewer(HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 72, T2));
            redis.expire(HeartRateRedisFixture.key(memberId), SHORT_TTL_SECONDS);

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.EMERGENCY, 180, T2));

            // then
            assertThat(updated).isFalse();
            HeartRateResult read = redis.repository().findByMemberId(memberId).orElseThrow();
            assertThat(read.getStatus()).isEqualTo(HeartRateStatus.NORMAL);
            assertThat(read.getHeartRate()).isEqualTo(72);
            assertThat(redis.ttl(HeartRateRedisFixture.key(memberId))).isBetween(1L, SHORT_TTL_SECONDS);
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("변경 전 형식으로 저장된 키에 더 이른 측정을 쓰면 덮어쓴다")
    void 변경_전_형식으로_저장된_키에_더_이른_측정을_쓰면_덮어쓴다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_005L;
            redis.repository().save(HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 72, T2));

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.CAUTION, 110, T1));

            // then
            assertThat(updated).isTrue();
            assertThat(redis.repository().findByMemberId(memberId).orElseThrow().getMeasuredAt()).isEqualTo(T1);
            assertThat(redis.hash(HeartRateRedisFixture.key(memberId)))
                    .containsKey(HeartRateLatestResultWriter.MEASURED_AT_EPOCH_MS);
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("비교 필드가 숫자가 아닌 키에 쓰면 새 값으로 덮어쓴다")
    void 비교_필드가_숫자가_아닌_키에_쓰면_새_값으로_덮어쓴다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long memberId = 780_900_006L;
            redis.writer().saveIfNewer(HeartRateResult.of(memberId, HeartRateStatus.NORMAL, 72, T2));
            redis.hset(HeartRateRedisFixture.key(memberId), HeartRateLatestResultWriter.MEASURED_AT_EPOCH_MS, "broken");

            // when
            boolean updated = redis.writer().saveIfNewer(
                    HeartRateResult.of(memberId, HeartRateStatus.CAUTION, 110, T1));

            // then
            assertThat(updated).isTrue();
            assertThat(redis.repository().findByMemberId(memberId).orElseThrow().getMeasuredAt()).isEqualTo(T1);
            redis.deleteMember(memberId);
        }
    }

    @Test
    @DisplayName("쓰기 빈으로 저장하면 Spring Data 저장 형식에 비교 필드 하나만 더한다")
    void 쓰기_빈으로_저장하면_Spring_Data_저장_형식에_비교_필드_하나만_더한다() {
        try (HeartRateRedisFixture redis = HeartRateRedisFixture.open()) {
            // given
            Long repositoryMemberId = 780_900_007L;
            Long writerMemberId = 780_900_008L;
            LocalDateTime measuredAt = LocalDateTime.of(2026, 10, 19, 9, 0, 0, 123_000_000);

            // when
            redis.repository().save(HeartRateResult.of(repositoryMemberId, HeartRateStatus.NORMAL, 72, measuredAt));
            redis.writer().saveIfNewer(HeartRateResult.of(writerMemberId, HeartRateStatus.NORMAL, 72, measuredAt));

            // then
            Map<String, String> repositoryHash = redis.hash(HeartRateRedisFixture.key(repositoryMemberId));
            Map<String, String> writerHash = redis.hash(HeartRateRedisFixture.key(writerMemberId));
            System.out.println("[FORMAT] repository.save HGETALL=" + repositoryHash
                    + " TTL=" + redis.ttl(HeartRateRedisFixture.key(repositoryMemberId)));
            System.out.println("[FORMAT] writer.saveIfNewer HGETALL=" + writerHash
                    + " TTL=" + redis.ttl(HeartRateRedisFixture.key(writerMemberId))
                    + " PHANTOM_EXISTS=" + redis.exists(HeartRateRedisFixture.key(writerMemberId) + ":phantom")
                    + " INDEX_TYPE=" + redis.type("heart_rate_result")
                    + " INDEX_HAS_MEMBER=" + redis.isMember("heart_rate_result", writerMemberId));
            Map<String, String> writerWithoutExtra = new TreeMap<>(writerHash);
            writerWithoutExtra.remove(HeartRateLatestResultWriter.MEASURED_AT_EPOCH_MS);
            writerWithoutExtra.put("memberId", String.valueOf(repositoryMemberId));
            assertThat(writerWithoutExtra).isEqualTo(repositoryHash);
            assertThat(redis.type(HeartRateRedisFixture.key(writerMemberId)))
                    .isEqualTo(redis.type(HeartRateRedisFixture.key(repositoryMemberId)));
            redis.deleteMember(repositoryMemberId);
            redis.deleteMember(writerMemberId);
        }
    }
}
