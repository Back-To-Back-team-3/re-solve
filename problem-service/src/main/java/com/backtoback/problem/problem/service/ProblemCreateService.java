package com.backtoback.problem.problem.service;

import java.util.ArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;

import lombok.RequiredArgsConstructor;

/** 기존 응답 조회·입력 검증·파일 업로드·짧은 원자 저장을 연결한다. */
@Service
@RequiredArgsConstructor
public class ProblemCreateService {
    private final ProblemCreateInput input;
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
        if (authorId <= 0 || key == null || key.isBlank() || key.length() > 100 || !key.matches("\\p{ASCII}+")
            || request == null) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        String hash = input.hash(request);
        var existing = store.replay(authorId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        var validated = input.validate(request);
        if (storage.bucket() == null || storage.bucket().isBlank()) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        var body = serializer.serializeBody(request.description(), request.constraints(), request.examples());
        Long bodyId = null;
        var testFiles = new ArrayList<ProblemCreateFiles.TestFile>();
        try {
            bodyId = uploads.upload(storage.bucket(), authorId, body, true);
            for (var test : request.testCases()) {
                var content = serializer.serializeTest(test);
                Long id = uploads.upload(storage.bucket(), authorId, content, false);
                testFiles.add(new ProblemCreateFiles.TestFile(id, content.sha256(), content.sizeBytes()));
            }
        } catch (RuntimeException failure) {
            if (bodyId != null) {
                recordFailure(new ProblemCreateFiles(bodyId, java.util.List.copyOf(testFiles)), failure);
            }
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        var command
            = new ProblemRegistrationCommand(
                null,
                null,
                request.scope(),
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
            return store.save(command, files, clientIp);
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
