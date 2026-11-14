package com.backtoback.problem.problem.dto.response;

import java.util.Arrays;
import java.util.List;

import com.backtoback.problem.problem.repository.ProblemSearchRepository.DiagnosisCandidate;

/** 난이도 1~3의 진단 후보를 고정된 위치에 담는 내부 응답. */
public record DiagnosisCandidatesResponse(String tagId, List<Candidate> problems) {
    /**
     * 후보가 없는 난이도의 위치는 null로 유지하고 ID를 문자열로 변환한다.
     *
     * @param tagId 진단 태그 ID
     * @param candidates 난이도별로 고른 후보
     * @return 난이도 순서의 진단 후보 응답
     */
    public static DiagnosisCandidatesResponse from(long tagId, List<DiagnosisCandidate> candidates) {
        var problems = new Candidate[3];
        for (var candidate : candidates) {
            problems[candidate.difficulty() - 1]
                = new Candidate(
                    candidate.difficulty(),
                    Long.toString(candidate.problemId()),
                    Long.toString(candidate.revisionId()),
                    candidate.title()
                );
        }
        return new DiagnosisCandidatesResponse(Long.toString(tagId), Arrays.asList(problems));
    }

    /** 진단에서 사용할 문제와 고정 회차. */
    public record Candidate(int difficulty, String problemId, String problemRevisionId, String title) {
    }
}
