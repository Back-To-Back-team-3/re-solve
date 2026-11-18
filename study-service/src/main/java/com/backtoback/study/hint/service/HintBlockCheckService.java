package com.backtoback.study.hint.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.hint.domain.HintBlockContextType;
import com.backtoback.study.hint.domain.HintBlockReplica;
import com.backtoback.study.hint.exception.HintBlockedDuringDiagnosisException;
import com.backtoback.study.hint.exception.HintBlockedDuringExamException;
import com.backtoback.study.hint.repository.HintBlockReplicaRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * hint_block_replicas 차단 판정 (api-spec §6.1.1 step②).
 * DB 오류 → fail-closed (503). @Transactional 없음 — 호출자가 트랜잭션 외부에서 선행 실행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HintBlockCheckService {

    // hint_requests.blocked_reason 값(ERD 4.6.1)
    public static final String BLOCKED_REASON_EXAM = "EXAM";
    public static final String BLOCKED_REASON_DIAGNOSIS = "DIAGNOSIS";

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

    /**
     * 회수 직전 차단 재확인 — 예외 대신 차단 사유를 돌려준다(scheduler §6.1, 정책 §8.1 ⑤⑥).
     * 판정 기준은 {@link #checkBlocked}와 같고, 조회가 실패하면 fail-closed로 시험 차단 사유를 돌려준다.
     *
     * @return 차단이면 {@code EXAM} 또는 {@code DIAGNOSIS}, 차단이 아니면 빈 값
     */
    public Optional<String> findBlockedReason(Long memberId, Long problemId) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime gracedNow = now.minusMinutes(GRACE_MINUTES);

        List<HintBlockReplica> blocks;
        try {
            blocks = hintBlockReplicaRepository.findActiveBlocks(memberId, problemId, now, gracedNow);
        } catch (DataAccessException e) {
            log.warn("차단 복제본 조회 실패 — fail-closed로 차단 처리 memberId={} problemId={}", memberId, problemId, e);
            return Optional.of(BLOCKED_REASON_EXAM);
        }

        return blocks
            .stream()
            .findFirst()
            .map(
                block -> block.getContextType() == HintBlockContextType.DIAGNOSIS ? BLOCKED_REASON_DIAGNOSIS
                    : BLOCKED_REASON_EXAM
            );
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
