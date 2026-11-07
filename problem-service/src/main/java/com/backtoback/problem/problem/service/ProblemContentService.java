package com.backtoback.problem.problem.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeStarterCodeClient;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.response.StarterCodeResponse;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 고정 본문·공개 예제·기본 코드를 트랜잭션 밖에서 조회한다. */
@Service
@RequiredArgsConstructor
public class ProblemContentService {
    private final ProblemContentStore store;
    private final ProblemAssetObjectReader reader;
    private final ProblemImageStorageProperties storage;
    private final JudgeStarterCodeClient judge;
    private final ObjectMapper mapper;

    /**
     * 숨김 테스트·검증 코드를 읽지 않고 열람 자료를 조합한다.
     *
     * @param problemId 문제
     * @param revisionId 내부 고정 회차 또는 일반 사용 회차 선택 시 null
     * @param context 내부 열람 문맥
     * @return 원문과 기본 코드
     * @throws ProblemException 원격 의존 장애
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Content read(long problemId, Long revisionId, String context) {
        var snapshot = store.content(problemId, revisionId, context);
        try {
            var body = mapper.readValue(bytes(snapshot.body()), Body.class);
            if (body.description() == null || body.constraints() == null || body.examples() == null) {
                throw new IllegalStateException("본문 필드가 없습니다.");
            }
            var samples = snapshot.samples().stream().map(sample -> {
                var test = mapper.readValue(bytes(sample.file()), Test.class);
                if (test.argumentTexts() == null || test.expectedReturnText() == null) {
                    throw new IllegalStateException("공개 예제가 손상되었습니다.");
                }
                return new Sample(sample.id(), test);
            }).toList();
            var languages = snapshot.fixed().profiles().stream().map(ProblemJudgingStore.Profile::language).toList();
            return new Content(snapshot.fixed(), body, samples, judge.read(snapshot.fixed().function(), languages));
        } catch (RuntimeException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 실제 MIME과 연결된 이미지 원문을 반환한다.
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @param imageId 이미지
     * @param context 내부 문맥 또는 일반 조회 시 null
     * @return 이미지 바이너리
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProblemImagePreviewService.Preview image(long problemId, long revisionId, long imageId, String context) {
        var snapshot = store.image(problemId, revisionId, imageId, context);
        try {
            return new ProblemImagePreviewService.Preview(snapshot.contentType(), bytes(snapshot.file()));
        } catch (RuntimeException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 불변 파일의 버전·체크섬·크기를 확인한다.
     *
     * @param file 불변 파일 참조
     * @return 검증된 바이트
     */
    private byte[] bytes(File file) {
        var bytes = reader.read(storage.bucket(), file.key(), file.version());
        var actual = ProblemAssetContent.fromBytes(bytes);
        if (actual.sizeBytes() != file.size() || !actual.sha256().equals(file.sha256())) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        return bytes;
    }

    /** 저장된 본문. */
    public record Body(String description, String constraints, String examples) {
    }
    /** 저장된 예제. */
    public record Test(Map<String, String> argumentTexts, String expectedReturnText) {
    }
    /** 공개 테스트 원문. */
    public record Sample(long id, Test content) {
    }
    /** 외부 열람에 허용된 고정 자료. */
    public record Content(
        ProblemJudgingStore.Snapshot fixed,
        Body body,
        List<Sample> samples,
        List<StarterCodeResponse> starterCodes
    ) {
    }
}
