package com.backtoback.study.hint.service;

import org.springframework.stereotype.Service;

import com.backtoback.study.hint.client.HintContextResponse;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.membership.service.MemberStatusGuard;

import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 오케스트레이터 (api-spec §6.1.1).
 * 외부 호출(step⓪①②)은 트랜잭션 외부에서 선행 실행하고, step③④⑤는 HintTransactionalService에 위임한다.
 *
 * @Transactional 없음 — diagrams.md §0.5 준수.
 */
@Service
@RequiredArgsConstructor
public class HintRequestService {

    private final MemberStatusGuard memberStatusGuard;
    private final HintValidationService hintValidationService;
    private final HintTransactionalService hintTransactionalService;

    public HintAcceptResult request(Long memberId, Long problemId, int level) {
        // step⓪: 정지 회원은 problem 문맥 조회 전에 거절한다 (member 조회 실패 → 503 fail-closed)
        memberStatusGuard.requireNotSuspended(memberId);

        // step①②: 외부 호출 → 트랜잭션 없이 실행
        HintContextResponse context = hintValidationService.validate(memberId, problemId);

        // step③④⑤: 트랜잭션 내 접수
        return hintTransactionalService.accept(memberId, problemId, context, level);
    }
}
