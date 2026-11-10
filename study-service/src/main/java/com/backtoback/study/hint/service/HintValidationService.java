package com.backtoback.study.hint.service;

import org.springframework.stereotype.Service;

import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.client.ProblemHintContextClient;
import com.backtoback.study.hint.exception.HintProblemNotAvailableException;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 5단계 검증 중 step①·② 오케스트레이션 (api-spec §6.1.1).
 *
 * @Transactional 없음 — diagrams.md §0.5: 외부 호출은 트랜잭션 외부에서 선행 실행한다.
 */
@Service
@RequiredArgsConstructor
public class HintValidationService {

    private final ProblemHintContextClient problemHintContextClient;
    private final HintBlockCheckService hintBlockCheckService;

    public HintContextResponse validate(Long memberId, Long problemId) {
        // step①: problem-service 힌트 문맥 조회 (실패 → 503 fail-closed)
        HintContextResponse context = problemHintContextClient.getHintContext(problemId);
        if (!context.isHintAvailable()) {
            throw new HintProblemNotAvailableException();
        }

        // step②: 차단 확인 (DB 오류 → 503 fail-closed, 차단 → 403)
        hintBlockCheckService.checkBlocked(memberId, problemId);

        return context;
    }
}
