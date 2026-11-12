package com.backtoback.problem.problem.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** DB 참조 고정 후 트랜잭션 밖에서 힌트 입력용 Markdown만 읽는다. (API §2.6.6) */
@Service
@RequiredArgsConstructor
public class ProblemHintContextService {
    // 정책 §8.1에 따라 전체 이벤트의 헤더·식별자용 여유 16KiB를 남긴다.
    private static final int MAX_HINT_INPUT_BYTES = 240 * 1024;
    private final ProblemHintContextStore store;
    private final ProblemAssetObjectReader reader;
    private final ProblemImageStorageProperties storage;
    private final ObjectMapper mapper;

    /**
     * 힌트 허용 자료만 조회하고 크기 초과는 비가용, 저장소 장애·손상 원문은 의존 장애로 거절한다.
     *
     * @param problemId 문제 식별자
     * @return Study에 제공할 힌트 가능 여부와 입력 자료
     * @throws ProblemException 잘못된 ID·미존재 문제 또는 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Context read(long problemId) {
        try {
            var snapshot = store.read(problemId);
            if (snapshot.body() == null) {
                return new Context(problemId, null, null, null, List.of(), false);
            }
            var file = snapshot.body();
            byte[] bytes = reader.read(storage.bucket(), file.key(), file.version());
            var actual = ProblemAssetContent.fromBytes(bytes);
            if (actual.sizeBytes() != file.size() || !actual.sha256().equals(file.sha256())) {
                throw new IllegalStateException("힌트 본문 크기 또는 체크섬이 일치하지 않습니다.");
            }
            var body = mapper.readValue(bytes, ProblemContentService.Body.class);
            if (body == null || body.description() == null || body.constraints() == null || body.examples() == null) {
                throw new IllegalStateException("힌트 본문 필드가 없습니다.");
            }
            String markdown
                = body.description() + "\n\n## 제한 사항\n\n" + body.constraints() + "\n\n## 예시\n\n" + body.examples();
            var input
                = Map.of("problemTitle", snapshot.title(), "problemBody", markdown, "problemTags", snapshot.tags());
            if (mapper.writeValueAsBytes(input).length > MAX_HINT_INPUT_BYTES) {
                return new Context(problemId, null, null, null, List.of(), false);
            }
            return new Context(problemId, snapshot.revisionId(), snapshot.title(), markdown, snapshot.tags(), true);
        } catch (ProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** Study가 사용할 고정 회차의 힌트 입력 또는 비가용 결과. */
    public record Context(
        long problemId,
        Long revisionId,
        String title,
        String body,
        List<String> tags,
        boolean available
    ) {
    }
}
