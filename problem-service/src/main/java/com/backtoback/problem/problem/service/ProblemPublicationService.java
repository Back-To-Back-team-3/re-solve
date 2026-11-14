package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ProblemPublicationRequest;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 공개 입력을 검증하고 커밋된 최초 작업 응답을 복원한다. */
@Service
@RequiredArgsConstructor
public class ProblemPublicationService {
    private final ProblemPublicationStore store;
    private final Validator validator;
    private final ObjectMapper mapper;

    /**
     * 원래 공개 요청을 접수하고 최초 결과를 반환한다.
     * 실패 작업 저장도 커밋한 뒤 오류를 전달하기 위해 외부 트랜잭션을 중지한다.
     *
     * @param problemId 대상 문제
     * @param actorId 작성 관리자
     * @param key 멱등키
     * @param request 원래 기대 버전
     * @param correlationId 공통 필터가 검증한 추적 ID
     * @param clientIp 감사 주소
     * @return 최초 종결 HTTP 상태와 공통 응답 JSON
     * @throws ProblemException 잘못된 입력은 {@code COMMON_INVALID_REQUEST}, 없는 문제는 {@code PROBLEM_NOT_FOUND},
     *     작성자 불일치는 {@code AUTH_ACCESS_DENIED}, 같은 키의 다른 요청은 {@code IDEMPOTENCY_KEY_CONFLICT},
     *     미종결 작업 충돌은 {@code PROBLEM_VERSION_CONFLICT}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result publish(
        long problemId,
        long actorId,
        String key,
        ProblemPublicationRequest request,
        String correlationId,
        String clientIp
    ) {
        if (problemId <= 0 || actorId <= 0 || key == null || key.isBlank() || !key.matches("[\\p{ASCII}]{1,100}")
            || request == null || !validator.validate(request).isEmpty() || correlationId == null
            || correlationId.isBlank() || correlationId.length() > 64) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var hash
            = ProblemAssetContent
                .fromBytes(mapper.writeValueAsBytes(List.of(problemId, actorId, "PUBLICATION", request.rowVersion())))
                .sha256();
        var operation
            = store
                .publish(
                    new ProblemPublicationStore.Command(
                        problemId,
                        actorId,
                        key,
                        hash,
                        request.rowVersion(),
                        correlationId,
                        clientIp
                    )
                );
        if ("PENDING".equals(operation.getState())) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        return new Result(operation.getResponseStatus(), operation.getResponseJson());
    }

    /**
     * 공통 응답 형식을 이미 포함한 최초 종결 응답이다.
     *
     * @param status 최초 HTTP 상태
     * @param json 최초 성공 또는 확정 실패 본문
     */
    public record Result(int status, String json) {
    }
}
