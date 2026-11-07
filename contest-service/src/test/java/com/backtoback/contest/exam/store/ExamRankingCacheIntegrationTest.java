package com.backtoback.contest.exam.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.exam.service.ExamResultQueryService;

/** 순위 캐시의 버전 역행 방지와 보관 기간 만료를 검증한다. */
class ExamRankingCacheIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("순위 캐시는 버전이 같을 때만 조회하고 역행을 거절한다")
    void rankingCacheRejectsOlderVersion() {
        long exam = Long.parseLong(examId);
        var newer = new ExamResultQueryService.Standing(participantId, "7", null, "20.00", 1, false);
        var older = new ExamResultQueryService.Standing(participantId, "7", null, "10.00", 1, false);
        rankingCache.replace(exam, 2, java.util.List.of(newer));
        rankingCache.replace(exam, 1, java.util.List.of(older));
        assertThat(rankingCache.read(exam, 1)).isNull();
        assertThat(rankingCache.read(exam, 2)).containsExactly(newer);
    }

    @Test
    @DisplayName("전체 순위 데이터와 현재 버전 포인터에 같은 유한 TTL을 설정한다")
    void rankingCacheExpiresDataAndPointerTogether() {
        long exam = Long.parseLong(examId);
        rankingCache.replace(exam, 2, List.of());
        String prefix = "exam:ranking:{" + exam + "}";
        assertThat(redis.getExpire(prefix + ":current", TimeUnit.SECONDS)).isBetween(1L, 3600L);
        assertThat(redis.getExpire(prefix + ":v:2", TimeUnit.SECONDS)).isBetween(1L, 3600L);
    }

    @Test
    @DisplayName("보관 만료 정리는 관측 버전 이하만 삭제하고 새 결과 버전은 보존한다")
    void expiredCacheEvictionPreservesNewerRevision() {
        long exam = Long.parseLong(examId);
        rankingCache.replace(exam, 2, List.of());
        rankingCache.evict(exam, 1);
        assertThat(rankingCache.read(exam, 2)).isEmpty();
        rankingCache.evict(exam, 2);
        assertThat(rankingCache.read(exam, 2)).isNull();
        assertThat(redis.hasKey("exam:ranking:{" + exam + "}:current")).isFalse();
        assertThat(redis.hasKey("exam:ranking:{" + exam + "}:v:2")).isFalse();
    }

}
