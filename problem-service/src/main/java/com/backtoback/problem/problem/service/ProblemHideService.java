package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.ContestGuardClient;
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.domain.ProblemVisibility;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/** 가드 준비·상태 커밋·완료 전송을 서로 다른 경계에서 실행한다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProblemHideService {
    private final ProblemHideStore store;
    private final ContestGuardClient guard;
    private final ObjectMapper mapper;

    /**
     * 동일 작업으로 재시도하여 공개 중단의 최초 결과를 반환한다.
     *
     * @param problemId 문제 ID
     * @param actorId 작성 관리자
     * @param key 원래 멱등키
     * @param version 기대 버전
     * @param archive 보관 여부
     * @param reason 보관 사유 또는 비공개 조치명
     * @param correlationId 추적 ID
     * @param clientIp 감사 주소
     * @return 최초 종결 응답
     * @throws ProblemException 입력 오류는 {@code COMMON_INVALID_REQUEST}, 작성자 불일치는
     *     {@code AUTH_ACCESS_DENIED}, 문제 없음은 {@code PROBLEM_NOT_FOUND}, 다른 본문의 동일 키는
     *     {@code IDEMPOTENCY_KEY_CONFLICT}, 다른 미종결 작업은 {@code PROBLEM_VERSION_CONFLICT},
     *     준비 실패·서킷 차단은 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProblemPublicationService.Result hide(
        long problemId,
        long actorId,
        String key,
        Integer version,
        boolean archive,
        String reason,
        String correlationId,
        String clientIp
    ) {
        if (problemId <= 0 || actorId <= 0 || version == null || version < 1 || key == null || key.isBlank()
            || !key.matches("[\\p{ASCII}]{1,100}") || reason == null || reason.isBlank() || reason.length() > 500
            || correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var target = archive ? ProblemVisibility.ARCHIVED : ProblemVisibility.PRIVATE;
        var hash
            = ProblemAssetContent
                .fromBytes(mapper.writeValueAsBytes(List.of(problemId, actorId, target, version, reason)))
                .sha256();
        var command
            = new ProblemHideStore.Command(
                problemId,
                actorId,
                key,
                hash,
                version,
                target,
                reason,
                correlationId,
                clientIp
            );
        var operation = store.begin(command);
        if ("PENDING".equals(operation.getState())) {
            ContestGuardClient.Rejection rejection;
            try {
                rejection = guard.prepare(operation);
            } catch (ProblemException uncertain) {
                // 같은 키의 경쟁 요청이 먼저 종결했다면 최초 결과를 복원한다.
                var latest = store.begin(command);
                if ("PENDING".equals(latest.getState())) {
                    throw uncertain;
                }
                if ("COMMITTED".equals(latest.getState())) {
                    notifyGuard(latest);
                }
                return new ProblemPublicationService.Result(latest.getResponseStatus(), latest.getResponseJson());
            }
            operation = store.finish(operation.getOperationId(), command, rejection);
            // 준비한 같은 작업의 확정 실패에만 취소를 전달한다.
            if (rejection == null || "COMMITTED".equals(operation.getState())) {
                notifyGuard(operation);
            }
        } else if ("COMMITTED".equals(operation.getState())) {
            notifyGuard(operation);
        }
        return new ProblemPublicationService.Result(operation.getResponseStatus(), operation.getResponseJson());
    }

    /**
     * 완료 전달 실패를 기록하고 이미 커밋된 원래 결과를 보존한다.
     *
     * @param operation 종결 작업
     */
    private void notifyGuard(ProblemStateOperation operation) {
        try {
            guard.finish(operation);
        } catch (RestClientException | CallNotPermittedException failure) {
            log.warn("문제 가드 종결 전달 실패: operationId={}", operation.getOperationId(), failure);
        }
    }
}
