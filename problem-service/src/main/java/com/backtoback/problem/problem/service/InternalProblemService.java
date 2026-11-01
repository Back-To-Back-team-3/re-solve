package com.backtoback.problem.problem.service;

import java.util.List;

import com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest;
import com.backtoback.problem.problem.dto.response.ContestProblemLinkResponse;
import com.backtoback.problem.problem.dto.response.JudgingDataResponse;
import com.backtoback.problem.problem.dto.response.JudgingValidationResponse;
import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;

/** Problem 내부 HTTP API의 업무 처리를 정의한다. */
public interface InternalProblemService {
    JudgingValidationResponse getJudgingValidation(String problemId, String revisionId);

    JudgingDataResponse getJudgingData(String problemId, String revisionId);

    ProblemContentResponse getContent(String problemId, String revisionId, String accessContext);

    byte[] getImage(String problemId, String revisionId, String imageId, String accessContext);

    ProblemSummaryListResponse getSummaries(List<String> ids, String purpose);

    ValidationDataResponse getValidationData(String validationId);

    ContestProblemLinkResponse linkContestProblem(String problemId, ContestProblemLinkRequest request);

    ProblemOperationResponse getOperation(String problemId, String operationId);
}
