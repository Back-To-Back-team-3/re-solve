package com.backtoback.problem.problem.service;

import java.util.ArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.dto.request.ProblemContentRequest;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.dto.response.ProblemRevisionResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 기존 응답 조회·입력 검증·파일 업로드·짧은 원자 저장을 연결한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemCreateService {
    private final ProblemCreateInput input;
    private final ProblemAssetReuseService reuse;
    private final ProblemCreateStore store;
    private final ProblemAssetSerializer serializer;
    private final ProblemAssetUploadService uploads;
    private final ProblemImageStorageProperties storage;

    /**
     * 관리자·키별 동일 요청은 파일을 다시 업로드하지 않고 최초 응답으로 처리한다.
     *
     * @param authorId 인증한 작성 관리자
     * @param key 최초 등록 멱등키
     * @param request 기본값 적용 전 전체 입력
     * @param clientIp 감사에 기록할 요청 주소
     * @return 최초 201 응답 데이터
     * @throws ProblemException 입력·멱등 충돌 또는 외부 파일 저장 실패
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProblemCreateResponse create(long authorId, String key, ProblemCreateRequest request, String clientIp) {
        validateIdentity(authorId, key, request);
        return register(null, null, request.scope(), authorId, key, request, input.hash(request), null, clientIp);
    }

    /**
     * 파일 업로드 중 DB 잠금 없이 새 회차를 등록하고 통과 코드 자동 검증을 예약한다.
     *
     * @param problemId 대상 문제
     * @param authorId 작성 관리자
     * @param key 등록 멱등키
     * @param request 원래 버전과 전체 입력
     * @param correlationId 공통 필터가 검증한 추적 ID
     * @param clientIp 감사 주소
     * @return 최초 새 회차 응답
     * @throws ProblemException 잘못된 입력은 {@code COMMON_INVALID_REQUEST}, 멱등 충돌은 {@code IDEMPOTENCY_KEY_CONFLICT},
     *     파일 저장 실패는 {@code COMMON_DEPENDENCY_UNAVAILABLE}, 대상·권한·버전·상태 오류는 사전 검증 규칙을 따른다
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProblemRevisionResponse revise(
        long problemId,
        long authorId,
        String key,
        ProblemRevisionRequest request,
        String correlationId,
        String clientIp
    ) {
        validateIdentity(authorId, key, request);
        if (problemId <= 0 || request.rowVersion() == null || request.rowVersion() < 1 || correlationId == null
            || !correlationId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return ProblemRevisionResponse
            .from(
                register(
                    problemId,
                    request.rowVersion(),
                    null,
                    authorId,
                    key,
                    request,
                    input.hash(problemId, request),
                    correlationId,
                    clientIp
                )
            );
    }

    /**
     * 파일 작업 전에 호출 식별자를 검증한다.
     *
     * @param authorId 관리자
     * @param key 멱등키
     * @param request 등록 입력
     * @throws ProblemException 식별자가 유효하지 않으면 {@code COMMON_INVALID_REQUEST}
     */
    private void validateIdentity(long authorId, String key, ProblemContentRequest request) {
        if (authorId <= 0 || key == null || key.isBlank() || key.length() > 100 || !key.matches("\\p{ASCII}+")
            || request == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 최초·새 회차의 입력 검증과 파일 연결 실패 복구를 공유한다.
     *
     * @param problemId 새 회차 대상, 최초 등록이면 null
     * @param version 새 회차의 기대 버전
     * @param scope 최초 등록의 용도
     * @param authorId 관리자
     * @param key 멱등키
     * @param request 전체 문제 입력
     * @param hash 업무 종류·대상을 포함한 원래 요청 해시
     * @param correlationId 자동 검증 추적 ID
     * @param clientIp 감사 주소
     * @return 최초 저장 응답
     * @throws ProblemException 입력·멱등·파일 저장 계약을 위반한 경우
     */
    private ProblemCreateResponse register(
        Long problemId,
        Integer version,
        ProblemScope scope,
        long authorId,
        String key,
        ProblemContentRequest request,
        String hash,
        String correlationId,
        String clientIp
    ) {
        var existing = store.replay(authorId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (problemId != null) {
            try {
                store.validateRevision(problemId, authorId, version);
            } catch (ProblemException rejected) {
                return store.replay(authorId, key, hash).orElseThrow(() -> rejected);
            }
        }
        var validated = input.validate(request);
        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        var body = serializer.serializeBody(request.description(), request.constraints(), request.examples());
        Long bodyId = null;
        var testFiles = new ArrayList<ProblemCreateFiles.TestFile>();
        try {
            bodyId = file(problemId, authorId, body, true);
            for (var test : request.testCases()) {
                var content = serializer.serializeTest(test);
                Long id = file(problemId, authorId, content, false);
                testFiles.add(new ProblemCreateFiles.TestFile(id, content.sha256(), content.sizeBytes()));
            }
        } catch (RuntimeException failure) {
            if (bodyId != null) {
                recordFailure(new ProblemCreateFiles(bodyId, java.util.List.copyOf(testFiles)), failure);
            }
            log.warn("문제 등록 파일 업로드 실패 authorId={}", authorId, failure);
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        var command
            = new ProblemRegistrationCommand(
                problemId,
                version,
                scope,
                authorId,
                key,
                hash,
                bodyId,
                body.sha256(),
                body.sizeBytes(),
                request.title(),
                request.difficulty(),
                request.functionSpec(),
                request.testCases(),
                validated.time(),
                validated.memory(),
                validated.wall(),
                validated.output(),
                validated.tagIds(),
                request.description(),
                request.constraints(),
                request.examples()
            );
        var files = new ProblemCreateFiles(bodyId, java.util.List.copyOf(testFiles));
        try {
            return problemId == null ? store.save(command, files, clientIp)
                : store.save(command, files, clientIp, correlationId);
        } catch (RuntimeException failure) {
            // UNIQUE 경합 또는 커밋 결과 불명은 새 트랜잭션으로 확정 결과를 확인한다. 파일은 즉시 삭제하지 않는다.
            java.util.Optional<ProblemCreateResponse> replay;
            try {
                replay = store.replay(authorId, key, hash);
            } catch (ProblemException conflict) {
                recordFailure(files, conflict);
                throw conflict;
            } catch (RuntimeException replayFailure) {
                if (failure != replayFailure) {
                    failure.addSuppressed(replayFailure);
                }
                recordFailure(files, failure);
                throw failure;
            }
            recordFailure(files, failure);
            if (replay.isPresent()) {
                return replay.get();
            }
            throw failure;
        }
    }

    /**
     * 같은 문제의 원문 일치 파일을 재사용하고 없으면 새 파일을 업로드한다.
     *
     * @param problemId 재사용 대상 문제, 최초 등록이면 null
     * @param authorId 관리자
     * @param content 불변 원문
     * @param body 본문 여부
     * @return 연결할 파일 ID
     */
    private long file(Long problemId, long authorId, ProblemAssetContent content, boolean body) {
        if (problemId != null) {
            var existing = reuse.find(storage.bucket(), problemId, authorId, content, body);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return uploads.upload(storage.bucket(), authorId, content, body);
    }

    /**
     * 원래 저장 오류를 보존하면서 미참조 파일에 연결 실패를 기록한다.
     *
     * @param files 업로드 완료한 파일
     * @param failure 호출자에게 전달할 원래 오류
     */
    private void recordFailure(ProblemCreateFiles files, RuntimeException failure) {
        try {
            store.recordLinkFailure(files);
        } catch (RuntimeException trackingFailure) {
            if (failure != trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
        }
    }
}
