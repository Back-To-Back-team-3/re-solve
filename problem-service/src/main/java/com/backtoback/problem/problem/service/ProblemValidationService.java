package com.backtoback.problem.problem.service;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/** 검증 코드 입력·멱등 조회·원격 업로드와 최종 원자 저장을 조정한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProblemValidationService {
    private final ProblemValidationStore store;
    private final ProblemAssetUploadService uploads;
    private final ProblemImageStorageProperties storage;
    private final Validator validator;
    private final JsonMapper canonical = JsonMapper.builder().build();

    /**
     * 작성 관리자 검증을 접수하고 원래 요청의 202 응답을 반환한다.
     *
     * @param problemId 소속 문제
     * @param revisionId 고정 회차
     * @param authorId 작성 관리자
     * @param key 멱등키
     * @param request 언어·코드 원문
     * @param correlationId 공통 필터가 검증한 추적 ID
     * @param clientIp 감사 주소
     * @return 최초 접수 응답
     * @throws ProblemException 잘못된 입력은 {@code COMMON_INVALID_REQUEST}, 코드 초과는 {@code CODE_TOO_LARGE},
     *     같은 키의 다른 요청은 {@code IDEMPOTENCY_KEY_CONFLICT}, 파일 저장 실패는 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProblemValidationResponse accept(
        long problemId,
        long revisionId,
        long authorId,
        String key,
        ProblemValidationRequest request,
        String correlationId,
        String clientIp
    ) {
        if (problemId <= 0 || revisionId <= 0 || authorId <= 0 || key == null || key.isBlank()
            || !key.matches("[\\p{ASCII}]{1,100}") || request == null || !validator.validate(request).isEmpty()
            || correlationId == null || correlationId.isBlank() || correlationId.length() > 64) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var code = encodeCode(request.sourceCode());
        if (code.sizeBytes() > 65536) {
            throw new ProblemException(ProblemErrorCode.CODE_TOO_LARGE);
        }
        String hash
            = ProblemAssetContent
                .fromBytes(
                    canonical
                        .writeValueAsBytes(
                            List
                                .of(
                                    "MANUAL_VALIDATION",
                                    problemId,
                                    revisionId,
                                    request.language(),
                                    request.sourceCode()
                                )
                        )
                )
                .sha256();
        var replay = store.replay(authorId, key, hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            store.validate(problemId, revisionId, authorId, request.language());
        } catch (ProblemException rejected) {
            // 최초 조회 뒤 경쟁 요청이 커밋되면 그 요청의 멱등 결과를 우선한다.
            return store.replay(authorId, key, hash).orElseThrow(() -> rejected);
        }
        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        long assetId;
        try {
            assetId = uploads.uploadCode(storage.bucket(), problemId, authorId, code);
        } catch (RuntimeException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
        var command
            = new ProblemValidationStore.Command(
                problemId,
                revisionId,
                authorId,
                request.language(),
                key,
                hash,
                code,
                correlationId,
                clientIp
            );
        try {
            return store.save(command, assetId);
        } catch (RuntimeException failure) {
            try {
                replay = store.replay(authorId, key, hash);
            } catch (ProblemException conflict) {
                recordFailure(assetId, conflict);
                throw conflict;
            } catch (RuntimeException replayFailure) {
                if (failure != replayFailure) {
                    failure.addSuppressed(replayFailure);
                }
                recordFailure(assetId, failure);
                throw failure;
            }
            if (replay.isPresent()) {
                recordFailure(assetId, failure);
                return replay.get();
            }
            recordFailure(assetId, failure);
            throw failure;
        }
    }

    /**
     * 코드 원문을 손실 없이 UTF-8로 변환한다. 짝 없는 surrogate를 대체 문자로 저장하지 않는다.
     *
     * @param sourceCode 원래 코드 문자열
     * @return 원문 바이트·체크섬·크기
     * @throws ProblemException 인코딩할 수 없는 문자열이면 {@code COMMON_INVALID_REQUEST}
     */
    private ProblemAssetContent encodeCode(String sourceCode) {
        try {
            var encoded
                = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(sourceCode));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return ProblemAssetContent.fromBytes(bytes);
        } catch (CharacterCodingException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 원래 오류를 유지하면서 미참조 파일을 실패 분류한다.
     *
     * @param assetId 코드 파일
     * @param failure 원래 오류
     */
    private void recordFailure(long assetId, RuntimeException failure) {
        try {
            store.recordLinkFailure(assetId);
        } catch (RuntimeException trackingFailure) {
            if (failure != trackingFailure) {
                failure.addSuppressed(trackingFailure);
            }
            log.warn("검증 코드 파일 실패 분류 기록 실패 assetId={}", assetId, trackingFailure);
        }
    }
}
