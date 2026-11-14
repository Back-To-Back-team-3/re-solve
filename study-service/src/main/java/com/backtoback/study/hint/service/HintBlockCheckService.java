package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.exception.HintBlockedDuringDiagnosisException;
import com.backtoback.study.hint.exception.HintBlockedDuringExamException;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * hint_block_replicas 차단 판정 (api-spec §6.1.1 step②).
 * DB 오류 → fail-closed (503). @Transactional 없음 — 호출자가 트랜잭션 외부에서 선행 실행한다.
 */
@Service
@RequiredArgsConstructor
public class HintBlockCheckService {

    private static final int GRACE_MINUTES = 10;

    private final Clock clock;
    private final HintBlockReplicaRepository hintBlockReplicaRepository;

    public void checkBlocked(Long memberId, Long problemId) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime gracedNow = now.minusMinutes(GRACE_MINUTES);

        var blocks = fetchBlocks(memberId, problemId, now, gracedNow);

        for (var block : blocks) {
            if (block.getContextType() == HintBlockContextType.DIAGNOSIS) {
                throw new HintBlockedDuringDiagnosisException();
            }
            throw new HintBlockedDuringExamException();
        }
    }

    private java.util.List<com.backtoback.study.hint.domain.HintBlockReplica> fetchBlocks(
        Long memberId,
        Long problemId,
        LocalDateTime now,
        LocalDateTime gracedNow
    ) {
        try {
            return hintBlockReplicaRepository.findActiveBlocks(memberId, problemId, now, gracedNow);
        } catch (DataAccessException e) {
            throw new StudyException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, e);
        }
    }
}
