package com.backtoback.study.assignment.dto;

import java.util.List;

import com.backtoback.study.assignment.repository.AssignmentProblemRepository.FollowUpCandidateView;

/**
 * 후속 문제집 후보 조회 응답 (API 명세서 §5.4.4).
 */
public record FollowUpCandidatesResponse(List<CandidateItem> candidates) {

    public record CandidateItem(String problemId, String title, int unsolvedMemberCount) {

    }

    public static FollowUpCandidatesResponse from(List<FollowUpCandidateView> views) {
        return new FollowUpCandidatesResponse(
            views
                .stream()
                .map(
                    v -> new CandidateItem(
                        String.valueOf(v.getProblemId()),
                        v.getTitle(),
                        v.getUnsolvedMemberCount().intValue()
                    )
                )
                .toList()
        );
    }
}
