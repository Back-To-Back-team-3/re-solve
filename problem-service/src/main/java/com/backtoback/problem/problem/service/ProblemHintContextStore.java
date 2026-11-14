package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import lombok.RequiredArgsConstructor;

/** 힌트에 허용된 현재 사용 회차와 불변 본문 참조만 조회한다. (API §2.6.6·정책 §8.1) */
@Service
@RequiredArgsConstructor
public class ProblemHintContextStore {
    private final ProblemReadStore reads;
    private final ProblemBodyAssetRepository bodies;

    /**
     * 같은 읽기 시점의 공개 상태·통과 회차·태그를 고정하고 비가용 문제의 원문은 제외한다.
     *
     * @param problemId 문제 식별자
     * @return 힌트 입력 참조 또는 원문을 제외한 비가용 결과
     * @throws ProblemException 식별자 오류는 {@code COMMON_INVALID_REQUEST}, 미존재는 {@code PROBLEM_NOT_FOUND},
     *     본문 참조 손상은 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Snapshot read(long problemId) {
        var current = reads.summaries(List.of(problemId), "ASSIGNMENT").getFirst();
        if (current.problem() == null) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        }
        if (!current.available()) {
            return new Snapshot(problemId, null, null, List.of(), null);
        }
        var revision = current.revision();
        var body
            = bodies
                .findById(revision.getBodyAssetId())
                .filter(
                    asset -> asset.getStatus() == ProblemAssetStatus.LINKED && asset.getObjectKey() != null
                        && !asset.getObjectKey().isBlank() && asset.getSha256() != null && asset.getSizeBytes() > 0
                )
                .orElseThrow(() -> new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        return new Snapshot(
            problemId,
            revision.getId(),
            revision.getTitle(),
            current.tags().stream().map(ProblemReadStore.Tag::name).toList(),
            new File(body.getObjectKey(), body.getObjectVersion(), body.getSha256(), body.getSizeBytes())
        );
    }

    /** 같은 조회 시점의 사용 회차·태그와 원격 읽기용 불변 파일 참조. */
    public record Snapshot(long problemId, Long revisionId, String title, List<String> tags, File body) {
    }
}
