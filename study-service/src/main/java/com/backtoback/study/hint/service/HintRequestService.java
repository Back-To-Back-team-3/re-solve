package com.backtoback.study.hint.service;

import org.springframework.stereotype.Service;

import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.dto.HintAcceptResult;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 오케스트레이터 (api-spec §6.1.1).
 * 외부 호출(step①②)은 트랜잭션 외부에서 선행 실행하고, step③④⑤는 HintTransactionalService에 위임한다.
 *
 * @Transactional 없음 — diagrams.md §0.5 준수.
 */
@Service
@RequiredArgsConstructor
public class HintRequestService {

    private final HintValidationService hintValidationService;
    private final HintTransactionalService hintTransactionalService;

    public HintAcceptResult request(Long memberId, Long problemId, int level) {
        // step①②: 외부 호출 → 트랜잭션 없이 실행
        HintContextResponse context = hintValidationService.validate(memberId, problemId);

        // step③④⑤: 트랜잭션 내 접수
        return hintTransactionalService.accept(memberId, problemId, context, level);
    }
}
