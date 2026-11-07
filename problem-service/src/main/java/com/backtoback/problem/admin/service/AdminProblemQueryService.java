package com.backtoback.problem.admin.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.service.ProblemAssetContent;
import com.backtoback.problem.problem.service.ProblemAssetObjectReader;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 고정 원문과 Judge 진행 상태를 DB 트랜잭션 밖에서 조합한다. */
@Service
@RequiredArgsConstructor
public class AdminProblemQueryService {
    private final AdminProblemQueryStore store;
    private final ProblemAssetObjectReader reader;
    private final ProblemImageStorageProperties storage;
    private final JudgeProgressClient judge;
    private final ObjectMapper mapper;

    /**
     * 작성자의 전체 회차 내용을 조회한다(API 명세서 §2.3.3·§2.3.5).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차 또는 최신 조회 시 null
     * @param author 작성자
     * @return 전체 내용과 언어별 진행
     * @throws ProblemException 원문이나 Judge 조회 실패 시 의존 장애
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Content read(long problemId, Long revisionId, long author) {
        if (revisionId != null && revisionId <= 0) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var snapshot = store.detail(problemId, revisionId, author);
        try {
            var body = mapper.readValue(readFile(snapshot.body()), Body.class);
            if (body.description() == null || body.constraints() == null || body.examples() == null) {
                throw new IllegalStateException("본문 필드가 누락되었습니다.");
            }
            var tests = snapshot.tests().stream().map(test -> {
                var content = mapper.readValue(readFile(test.file()), StoredTest.class);
                if (content.argumentTexts() == null || content.expectedReturnText() == null) {
                    throw new IllegalStateException("테스트 원문이 누락되었습니다.");
                }
                return new Test(test.id(), test.sample(), content);
            }).toList();
            var languages
                = snapshot
                    .languages()
                    .stream()
                    .map(
                        language -> new Language(
                            language,
                            readFile(language.code()),
                            judge
                                .read(
                                    Long.toString(language.validationId()),
                                    snapshot.summary().revision().getId().toString(),
                                    language.language()
                                )
                        )
                    )
                    .toList();
            return new Content(
                snapshot,
                body,
                mapper.readValue(snapshot.summary().revision().getFunctionSpecJson(), FunctionSpec.class),
                tests,
                languages
            );
        } catch (RuntimeException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 파일 버전·크기·체크섬을 검사하고 엄격한 UTF-8로 읽는다.
     *
     * @param file 고정 참조
     * @return 원문
     * @throws ProblemException 파일 손상 또는 읽기 실패
     */
    private String readFile(File file) {
        var bytes = reader.read(storage.bucket(), file.key(), file.version());
        var content = ProblemAssetContent.fromBytes(bytes);
        if (file.size() != content.sizeBytes() || !file.sha256().equals(content.sha256())) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        try {
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException invalid) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /** 저장된 본문 세 영역. */
    public record Body(String description, String constraints, String examples) {
    }
    /** 저장된 테스트 입력과 기대값. */
    public record StoredTest(Map<String, String> argumentTexts, String expectedReturnText) {
    }
    /** 테스트 원문과 조회 전용 식별자. */
    public record Test(long id, boolean sample, StoredTest content) {
    }
    /** 코드와 최신 검증 진행. */
    public record Language(AdminProblemQueryStore.Language local, String code, JudgeProgressClient.Progress progress) {
    }
    /** 작성자가 조회한 고정 회차의 전체 내용. */
    public record Content(
        AdminProblemQueryStore.Detail snapshot,
        Body body,
        FunctionSpec function,
        List<Test> tests,
        List<Language> languages
    ) {
    }
}
