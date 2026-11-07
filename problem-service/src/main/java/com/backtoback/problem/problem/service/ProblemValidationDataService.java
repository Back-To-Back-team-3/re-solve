package com.backtoback.problem.problem.service;

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
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.response.ValidationCaseResponse;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 원래 요청의 S3 버전·체크섬을 대조하고 고정 검증 자료를 조합한다. */
@Service
@RequiredArgsConstructor
public class ProblemValidationDataService {
    private final ProblemValidationDataStore store;
    private final ProblemAssetObjectReader reader;
    private final ProblemImageStorageProperties storage;
    private final ObjectMapper mapper;

    /**
     * DB 트랜잭션 밖에서 원래 코드와 전체 테스트 원문을 읽는다.
     *
     * @param validationId 요청 ID
     * @return 공통 래퍼 없는 고정 자료
     * @throws ProblemException 요청 없음은 {@code PROBLEM_VALIDATION_NOT_FOUND}, 파일·체크섬 오류는
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ValidationDataResponse read(long validationId) {
        var snapshot = store.read(validationId);
        try {
            String code = utf8(readFile(snapshot.code()));
            Map<String, Object> function
                = mapper
                    .readValue(
                        snapshot.function(),
                        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                    );
            List<ValidationCaseResponse> tests = snapshot.tests().stream().map(test -> {
                var content = mapper.readValue(readFile(test.file()), StoredTest.class);
                if (content.argumentTexts() == null || content.expectedReturnText() == null) {
                    throw new IllegalStateException("저장된 테스트 원문이 올바르지 않습니다.");
                }
                return ValidationCaseResponse.from(test, content.argumentTexts(), content.expectedReturnText());
            }).toList();
            return ValidationDataResponse.from(snapshot, code, function, tests);
        } catch (RuntimeException | CharacterCodingException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 고정 버전 원문의 크기·체크섬을 대조한다.
     *
     * @param file 고정 참조
     * @return 검증된 원문
     * @throws IllegalStateException 참조와 원문이 불일치한 경우
     */
    private byte[] readFile(ProblemValidationDataStore.File file) {
        var bytes = reader.read(storage.bucket(), file.key(), file.version());
        var content = ProblemAssetContent.fromBytes(bytes);
        if (content.sizeBytes() != file.size() || !content.sha256().equals(file.sha256())) {
            throw new IllegalStateException("고정 파일 체크섬 또는 크기가 일치하지 않습니다.");
        }
        return bytes;
    }

    /**
     * 잘못된 바이트를 대체하지 않고 UTF-8 코드 원문을 복원한다.
     *
     * @param bytes 저장된 원문
     * @return 코드 문자열
     * @throws CharacterCodingException 올바른 UTF-8이 아닌 경우
     */
    private String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    /** 업로드 시 저장한 테스트 JSON의 구조. */
    private record StoredTest(Map<String, String> argumentTexts, String expectedReturnText) {
    }
}
