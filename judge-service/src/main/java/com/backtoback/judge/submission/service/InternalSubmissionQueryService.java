package com.backtoback.judge.submission.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** Contest의 제출 대사와 시험 복기에 필요한 제출만 조회한다. */
@Service
@RequiredArgsConstructor
public class InternalSubmissionQueryService {
    private final SubmissionRepository submissions;

    /**
     * 시험·대회 ID가 일치하는 제출의 현재 채점 상태를 조회한다.
     *
     * @param submissionId Judge 제출 ID
     * @param contextId Contest의 시험·대회 ID
     * @return 현재 상태의 제출
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 시험·대회 문맥 및 ID가 일치하지 않는 경우
     */
    @Transactional(readOnly = true)
    public Submission result(long submissionId, long contextId) {
        return submissions
            .findById(submissionId)
            .filter(
                submission -> (submission.getContext() == Submission.Context.EXAM
                    || submission.getContext() == Submission.Context.CONTEST)
                    && Long.valueOf(contextId).equals(submission.getContextId())
            )
            .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * 시험 ID와 제출 소유자가 일치하는 복기용 코드를 조회한다. 공개 시점 판단은 Contest가 맡는다.
     *
     * @param submissionId Judge 제출 ID
     * @param memberId Contest에서 인증한 요청자 ID
     * @param contextId Contest의 시험 ID
     * @return 소유자와 시험 ID가 일치하는 제출
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 소유자·시험 ID가 일치하지 않는 경우
     */
    @Transactional(readOnly = true)
    public Submission examSource(long submissionId, long memberId, long contextId) {
        return submissions
            .findByIdAndMemberIdAndContext(submissionId, memberId, Submission.Context.EXAM)
            .filter(submission -> Long.valueOf(contextId).equals(submission.getContextId()))
            .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * Contest의 문맥과 원래 접수 ID로 제출을 대사한다.
     *
     * @param context 요청 검증을 마친 시험·대회 문맥
     * @param receiptId Contest의 원래 접수 ID
     * @return 원래 접수에 연결된 제출; 아직 접수되지 않았다면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<Submission> byReceipt(Submission.Context context, long receiptId) {
        return submissions.findByContextAndSourceSubmissionId(context, receiptId);
    }
}
