package com.backtoback.member.skill.dto;

import static com.backtoback.member.skill.dto.DiagnosisStartResponse.offset;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.member.skill.domain.DiagnosisAttempt;
import com.backtoback.member.skill.domain.DiagnosisStatus;
import com.backtoback.member.skill.service.DiagnosisService.DiagnosisDetail;
import com.backtoback.member.skill.store.DiagnosisDraftStore.Draft;
import com.backtoback.member.skill.store.DiagnosisDraftStore.LastSubmission;

/**
 * 진단 상세·복구 응답 (API 명세서 1.5.2). 판정은 진단이 끝나 {@code SCORED}가 된 뒤에만 {@code result}로 공개한다.
 */
public record DiagnosisDetailResponse(
    String diagnosisId,
    String tagId,
    String status,
    OffsetDateTime endsAt,
    OffsetDateTime serverNow,
    List<Problem> problems,
    Result result
) {

    public static DiagnosisDetailResponse from(DiagnosisDetail detail) {
        DiagnosisAttempt attempt = detail.attempt();
        return new DiagnosisDetailResponse(
            String.valueOf(attempt.getId()),
            String.valueOf(attempt.getTagId()),
            attempt.getStatus().name(),
            offset(attempt.getEndsAt()),
            offset(detail.serverNow()),
            detail.problems().stream().map(problem -> {
                Draft draft = detail.drafts().get(problem.getProblemId());
                LastSubmission submission = detail.lastSubmissions().get(problem.getProblemId());
                return new Problem(
                    String.valueOf(problem.getProblemId()),
                    String.valueOf(problem.getProblemRevisionId()),
                    problem.getDifficulty(),
                    draft == null ? null
                        : new DraftView(
                            draft.language(),
                            draft.sourceCode(),
                            String.valueOf(draft.seq()),
                            offset(draft.savedAt())
                        ),
                    submission == null ? null
                        : new LastSubmissionView(submission.status(), String.valueOf(submission.draftSeq()))
                );
            }).toList(),
            attempt.getStatus() == DiagnosisStatus.SCORED
                ? new Result(attempt.getScore(), attempt.getResultLevel().name(), null) : null
        );
    }

    public record Problem(
        String problemId,
        String problemRevisionId,
        int difficulty,
        DraftView draft,
        LastSubmissionView lastSubmission
    ) {
    }

    public record DraftView(String language, String sourceCode, String seq, OffsetDateTime savedAt) {
    }

    public record LastSubmissionView(String submissionStatus, String draftSeq) {
    }

    /**
     * @param appliedLevel 진단 반영 후 레벨. 결과 확정(③)을 구현할 때 채운다.
     */
    public record Result(BigDecimal score, String resultLevel, String appliedLevel) {
    }
}
