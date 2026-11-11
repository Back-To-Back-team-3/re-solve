package com.backtoback.judge.submission.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.repository.SubmissionRepository;

import lombok.RequiredArgsConstructor;

/** 시험·대회·진단 제출 대사와 시험·진단 복기에 필요한 제출만 조회한다. */
@Service
@RequiredArgsConstructor
public class InternalSubmissionQueryService {
    private final SubmissionRepository submissions;

    /**
     * 시험·대회·진단 ID가 일치하는 제출의 현재 채점 상태를 조회한다.
     *
     * @param submissionId Judge 제출 ID
     * @param contextId 원래 시험·대회·진단 ID
     * @return 현재 상태의 제출
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 시험·대회·진단 문맥 및 ID가 일치하지 않는 경우
     */
    @Transactional(readOnly = true)
    public Submission result(long submissionId, long contextId) {
        return submissions
            .findById(submissionId)
            .filter(
                submission -> submission.getContext() != Submission.Context.PRACTICE
                    && Long.valueOf(contextId).equals(submission.getContextId())
            )
            .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * 문맥과 문맥 ID, 제출 소유자가 일치하는 복기용 코드를 조회한다. 공개 판단은 문맥 소유 서비스가 맡는다.
     *
     * @param submissionId Judge 제출 ID
     * @param memberId 문맥 소유 서비스에서 인증한 요청자 ID
     * @param context 복기 목적에 해당하는 시험 또는 진단 문맥
     * @param contextId 시험 또는 진단 ID
     * @return 소유자·문맥·문맥 ID가 일치하는 제출
     * @throws JudgeException {@code SUBMISSION_NOT_FOUND}: 제출이 없거나 소유자·문맥·문맥 ID가 일치하지 않는 경우
     */
    @Transactional(readOnly = true)
    public Submission reviewSource(long submissionId, long memberId, Submission.Context context, long contextId) {
        return submissions
            .findByIdAndMemberIdAndContext(submissionId, memberId, context)
            .filter(submission -> Long.valueOf(contextId).equals(submission.getContextId()))
            .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_NOT_FOUND));
    }

    /**
     * 시험·대회·진단 문맥과 원래 접수 ID로 제출을 대사한다.
     *
     * @param context 요청 검증을 마친 시험·대회·진단 문맥
     * @param receiptId Contest 또는 Member의 원래 접수 ID
     * @return 원래 접수에 연결된 제출; 아직 접수되지 않았다면 빈 값
     */
    @Transactional(readOnly = true)
    public Optional<Submission> byReceipt(Submission.Context context, long receiptId) {
        return submissions.findByContextAndSourceSubmissionId(context, receiptId);
    }
}
