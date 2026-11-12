package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import tools.jackson.databind.json.JsonMapper;

/** 직렬화 후 힌트 입력 크기와 이벤트 전체 크기의 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ProblemHintContextSizeTest {
    private static final int INPUT_LIMIT = 240 * 1024;
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Mock
    private ProblemHintContextStore store;
    @Mock
    private ProblemAssetObjectReader reader;

    /**
     * 한글·이스케이프와 제목·태그를 포함한 직렬화 크기가 정확히 상한이면 허용한다.
     *
     * @param prefix 본문에 포함할 문자 유형
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ascii",
            "한글",
            "\"\\\n\t"
        }
    )
    @DisplayName("UTF-8 JSON 입력이 정확히 240KiB이면 허용하고 전체 이벤트도 256KiB 이내다")
    void acceptsExactLimit(String prefix) {
        var result = readAtSize(prefix, INPUT_LIMIT);
        assertThat(result.available()).isTrue();
        assertThat(result.revisionId()).isEqualTo(2L);
        var input = input(result.title(), result.body(), result.tags());
        assertThat(mapper.writeValueAsBytes(input)).hasSize(INPUT_LIMIT);

        // S9의 추가 필드와 공통 헤더를 실제 공통 코덱으로 합쳐 남겨 둔 여유를 확인한다.
        var payload = mapper.valueToTree(input);
        var object = (tools.jackson.databind.node.ObjectNode)payload;
        for (String field : List.of("hintRequestId", "memberId", "problemId", "problemRevisionId")) {
            object.put(field, Long.toString(Long.MAX_VALUE));
        }
        object.put("level", 3);
        var metadata
            = new EventMetadata(
                UUID.randomUUID(),
                "HintRequested",
                "v1",
                OffsetDateTime.parse("2026-11-12T12:00:00.123456+09:00"),
                "9".repeat(64),
                "c".repeat(64)
            );
        String event = new EventJsonCodec(mapper).serialize(metadata, object);
        assertThat(event.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256 * 1024);
    }

    /**
     * 본문을 자르는 대신 기존 비가용 응답으로 모든 힌트 입력을 제외한다.
     *
     * @param prefix 본문에 포함할 문자 유형
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ascii",
            "한글",
            "\"\\\n\t"
        }
    )
    @DisplayName("UTF-8 JSON 입력이 240KiB보다 1바이트 크면 false·null·빈 태그를 반환한다")
    void rejectsOneByteOverLimit(String prefix) {
        var result = readAtSize(prefix, INPUT_LIMIT + 1);
        assertThat(result.problemId()).isEqualTo(1);
        assertThat(result.available()).isFalse();
        assertThat(result.revisionId()).isNull();
        assertThat(result.title()).isNull();
        assertThat(result.body()).isNull();
        assertThat(result.tags()).isEmpty();
    }

    /**
     * 제목·태그·Markdown 구분자까지 계산해 목표 바이트 크기의 원문을 조회한다.
     *
     * @param prefix 본문의 첫 부분
     * @param targetBytes 직렬화한 힌트 입력의 목표 바이트 크기
     * @return 서비스의 크기 검증 결과
     */
    private ProblemHintContextService.Context readAtSize(String prefix, int targetBytes) {
        String title = "제목\"".repeat(40);
        var tags = List.of("태그\\".repeat(40), "그래프");
        String suffix = "\n\n## 제한 사항\n\n제한\n\n## 예시\n\n예시";
        int base = mapper.writeValueAsBytes(input(title, prefix + suffix, tags)).length;
        String description = prefix + "a".repeat(targetBytes - base);
        assertThat(mapper.writeValueAsBytes(input(title, description + suffix, tags))).hasSize(targetBytes);
        byte[] bytes = mapper.writeValueAsBytes(new ProblemContentService.Body(description, "제한", "예시"));
        var content = ProblemAssetContent.fromBytes(bytes);
        given(store.read(1))
            .willReturn(
                new ProblemHintContextStore.Snapshot(
                    1,
                    2L,
                    title,
                    tags,
                    new File("body-key", "version-1", content.sha256(), content.sizeBytes())
                )
            );
        given(reader.read("test-bucket", "body-key", "version-1")).willReturn(bytes);
        return new ProblemHintContextService(store, reader, new ProblemImageStorageProperties("test-bucket"), mapper)
            .read(1);
    }

    /**
     * S9와 같은 이름으로 크기 산정 대상 세 필드를 구성한다.
     *
     * @param title 문제 제목
     * @param body 조합한 Markdown 본문
     * @param tags 태그 이름 목록
     * @return 직렬화할 힌트 입력 객체
     */
    private Map<String, Object> input(String title, String body, List<String> tags) {
        return Map.of("problemTitle", title, "problemBody", body, "problemTags", tags);
    }
}
