package com.backtoback.contest.exam.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.exam.service.ExamResultQueryService;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
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

}
