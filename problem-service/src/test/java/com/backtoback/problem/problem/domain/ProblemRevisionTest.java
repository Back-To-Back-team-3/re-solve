package com.backtoback.problem.problem.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProblemRevisionTest {

    @Test
    @DisplayName("회차를 생성하면 전달한 제한값·명세·등록 멱등 정보가 그대로 저장된다")
    void createRecordsRevision() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 29, 10, 0);

        ProblemRevision revision
            = ProblemRevision
                .create(
                    1L,
                    1,
                    2L,
                    "제목",
                    2,
                    "{}",
                    2000,
                    262144,
                    2500,
                    64,
                    3L,
                    "idempotency-key",
                    "a".repeat(64),
                    1,
                    createdAt
                );

        assertThat(revision.getProblemId()).isEqualTo(1L);
        assertThat(revision.getRevisionNumber()).isEqualTo(1);
        assertThat(revision.getBodyAssetId()).isEqualTo(2L);
        assertThat(revision.getTitle()).isEqualTo("제목");
        assertThat(revision.getDifficulty()).isEqualTo(2);
        assertThat(revision.getFunctionSpecJson()).isEqualTo("{}");
        assertThat(revision.getTimeLimitMs()).isEqualTo(2000);
        assertThat(revision.getMemoryLimitKb()).isEqualTo(262144);
        assertThat(revision.getWallTimeLimitMs()).isEqualTo(2500);
        assertThat(revision.getOutputLimitKb()).isEqualTo(64);
        assertThat(revision.getAuthorId()).isEqualTo(3L);
        assertThat(revision.getIdempotencyKey()).isEqualTo("idempotency-key");
        assertThat(revision.getRequestHash()).isEqualTo("a".repeat(64));
        assertThat(revision.getResponseRowVersion()).isEqualTo(1);
        assertThat(revision.getCreatedAt()).isEqualTo(createdAt);
    }
}
