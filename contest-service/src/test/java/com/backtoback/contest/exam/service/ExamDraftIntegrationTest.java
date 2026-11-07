package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** Draft 순번·크기 제한과 Redis 버퍼의 저장 순서를 검증한다. */
class ExamDraftIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("언어를 바꿔도 작은 순번을 무시하고 빈 최신 코드를 복구한다")
    void sequenceAcrossLanguages() {
        // given
        var first = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "5"));
        var older = store.prepare(examId, "7", "101", new SaveRequest("cpp", "이전", "4"));
        var equal = store.prepare(examId, "7", "101", new SaveRequest("python3", "동일", "5"));
        var empty = store.prepare(examId, "7", "101", new SaveRequest("cpp", "", "6"));
        // when
        assertThat(store.save(first).applied()).isTrue();
        var ignored = store.save(older);
        assertThat(store.save(equal).applied()).isFalse();
        assertThat(store.save(empty).applied()).isTrue();
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        var recovered = store.read(examId, "7");
        // then
        assertThat(ignored.applied()).isFalse();
        assertThat(ignored.serverSeq()).isEqualTo("5");
        assertThat(recovered.content()).extracting("sourceCode").containsExactlyInAnyOrder("코드", "");
        assertThat(recovered.problemSequences()).extracting("serverSeq").containsExactly("6", "0");
    }

    @Test
    @DisplayName("이벤트 크기를 초과한 신규 Draft는 기존 저장본을 보존한다")
    void oversizedEventPreservesDraft() {
        // given
        var first = store.prepare(examId, "7", "101", new SaveRequest("java", "기존", "1"));
        var oversized
            = store.prepare(examId, "7", "101", new SaveRequest("java", String.valueOf((char)1).repeat(50000), "2"));
        assertThat(store.save(first).applied()).isTrue();
        // when & then
        assertThatThrownBy(() -> store.save(oversized))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.CODE_TOO_LARGE)
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM exam_drafts WHERE exam_participant_id=?",
                    String.class,
                    participantId
                )
        ).isEqualTo("기존");
        assertThat(
            jdbc.queryForObject("SELECT seq FROM exam_drafts WHERE exam_participant_id=?", Long.class, participantId)
        ).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 참가자의 버퍼에서 밀린 요청은 미저장 문제의 순번 0과 null 시각을 반환한다")
    void bufferKeepsLastRequest() {
        // given
        String prefix = "exam:draft:buffer:" + participantId;
        var openSlot = new DefaultRedisScript<Long>("""
            local time = redis.call('TIME')
            local due = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + 60000
            redis.call('SET', KEYS[1], string.format('%.0f', due), 'PX', 120000)
            return due
            """, Long.class);
        long due = redis.execute(openSlot, List.of(prefix + ":due"));
        String slot = prefix + ":slot:" + due;
        var first = store.prepare(examId, "7", "102", new SaveRequest("java", "첫 코드", "1"));
        var last = store.prepare(examId, "7", "101", new SaveRequest("java", "마지막", "1"));
        var enqueue = bufferScript("ENQUEUE", List.class);
        var close = bufferScript("CLOSE_SLOT", List.class);

        List<?> firstSlot = redis.execute(enqueue, List.of(prefix), "first");
        List<?> lastSlot = redis.execute(enqueue, List.of(prefix), "last");
        assertThat(firstSlot.getFirst()).isEqualTo(due);
        assertThat(lastSlot.getFirst()).isEqualTo(due);
        List<?> closed = redis.execute(close, List.of(slot), "0");
        assertThat(closed.get(1)).isEqualTo("last");

        var saved = store.save(last);
        redis.execute(bufferScript("RECORD_STATUS", Long.class), List.of(slot), "done");
        var skipped = store.skipped(first);

        assertThat(saved.applied()).isTrue();
        assertThat(skipped.applied()).isFalse();
        assertThat(skipped.serverSeq()).isEqualTo("0");
        assertThat(skipped.savedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isEqualTo(1);
        assertThat(redis.getExpire(slot)).isPositive();
    }

    private <T> DefaultRedisScript<T> bufferScript(String field, Class<T> resultType) {
        var script = (RedisScript<?>)ReflectionTestUtils.getField(ExamDraftBuffer.class, field);
        return new DefaultRedisScript<>(script.getScriptAsString(), resultType);
    }

    @Test
    @DisplayName("버퍼에서 밀린 요청도 개인 마감이 지나면 저장 가능 응답을 반환하지 않는다")
    void skippedRequestRechecksDeadline() {
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        jdbc
            .update(
                "UPDATE exam_participants SET personal_ends_at=DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id=?",
                participantId
            );

        assertThatThrownBy(() -> store.skipped(work))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(ContestErrorCode.EXAM_CLOSED);
    }

    @Test
    @DisplayName("부모 잠금 대기 중 개인 마감이 바뀌면 저장하지 않는다")
    void rechecksDeadlineAfterLock() throws Exception {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE exams SET version=version+1 WHERE id=?", examId);
                jdbc
                    .update(
                        "UPDATE exam_participants SET personal_ends_at=? WHERE id=?",
                        LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusSeconds(1),
                        participantId
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // when & then
            var save = pool.submit(() -> store.save(work));
            try {
                assertThatThrownBy(() -> save.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> save.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_CLOSED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }

    @Test
    @DisplayName("서로 다른 언어의 동시 저장은 큰 순번을 최신 Draft로 유지한다")
    void concurrentLanguagesKeepMaximum() throws Exception {
        // given
        var smaller = store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1"));
        var larger = store.prepare(examId, "7", "101", new SaveRequest("python3", "최신", "2"));
        var start = new CountDownLatch(1);
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.save(smaller);
            });
            var second = pool.submit(() -> {
                start.await();
                return store.save(larger);
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS).applied()).isTrue();
        }
        // then
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT MAX(seq) FROM exam_drafts WHERE exam_participant_id=? AND problem_id=101",
                    Long.class,
                    participantId
                )
        ).isEqualTo(2L);
    }

    @Test
    @DisplayName("정지 회원은 새 Draft를 저장할 수 없다")
    void suspendedCannotSave() {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when & then
        assertThatThrownBy(() -> store.save(work))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }

    @Test
    @DisplayName("버퍼 마감 전에 깨어나도 마감 직전의 마지막 요청을 선택한다")
    void waitsForRedisDeadline() throws Exception {
        // given
        var timeScript = new DefaultRedisScript<Long>("""
            local time = redis.call('TIME')
            return tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + 1000
            """, Long.class);
        long due = redis.execute(timeScript, java.util.List.of());
        String key = "exam:draft:buffer:" + participantId + ":slot:" + due;
        redis.opsForHash().put(key, "last", "first");
        try (var pool = Executors.newSingleThreadExecutor()) {
            // when
            var result
                = pool.submit(() -> (String)ReflectionTestUtils.invokeMethod(buffer, "lastAfterDeadline", key, due));
            assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            redis.opsForHash().put(key, "last", "last");
            // then
            assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo("last");
        }
    }
}
