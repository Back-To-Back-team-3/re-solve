package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import tools.jackson.databind.json.JsonMapper;

/** 고정 본문을 Study 입력으로 조합하고 불필요한 원문 조회·손상 입력을 차단한다. */
@ExtendWith(MockitoExtension.class)
class ProblemHintContextServiceTest {
    private static final byte[] BODY = """
        {"description":"문제 설명","constraints":"N <= 100","examples":"예시 입력과 출력"}
        """.getBytes(StandardCharsets.UTF_8);
    @Mock
    private ProblemHintContextStore store;
    @Mock
    private ProblemAssetObjectReader reader;
    private ProblemHintContextService service;

    /** 기존 본문 버킷·매퍼를 사용하는 서비스를 준비한다. */
    @BeforeEach
    void prepare() {
        service
            = new ProblemHintContextService(
                store,
                reader,
                new ProblemImageStorageProperties("test-bucket"),
                JsonMapper.builder().build()
            );
    }

    /** 사용 회차와 태그를 그대로 유지하고 본문의 세 공개 영역을 Markdown으로 조합한다. */
    @Test
    @DisplayName("고정 객체 버전의 설명·제한·예시를 힌트 본문으로 조합하고 빈 태그도 허용한다")
    void readsHintBody() {
        given(store.read(1)).willReturn(snapshot(BODY, false, false));
        given(reader.read("test-bucket", "body-key", "version-1")).willReturn(BODY);
        var result = service.read(1);
        assertThat(result.revisionId()).isEqualTo(2);
        assertThat(result.title()).isEqualTo("현재 통과 제목");
        assertThat(result.body()).isEqualTo("문제 설명\n\n## 제한 사항\n\nN <= 100\n\n## 예시\n\n예시 입력과 출력");
        assertThat(result.tags()).isEmpty();
        assertThat(result.available()).isTrue();
        verify(reader).read("test-bucket", "body-key", "version-1");
    }

    /** 비가용 문제의 본문을 저장소에서 읽지 않는다. */
    @Test
    @DisplayName("비가용 문제는 원문을 조회하지 않고 false와 null 입력을 반환한다")
    void skipsUnavailableBody() {
        given(store.read(1)).willReturn(new ProblemHintContextStore.Snapshot(1, null, null, List.of(), null));
        var result = service.read(1);
        assertThat(result.available()).isFalse();
        assertThat(result.revisionId()).isNull();
        assertThat(result.title()).isNull();
        assertThat(result.body()).isNull();
        assertThat(result.tags()).isEmpty();
        verifyNoInteractions(reader);
    }

    /**
     * 체크섬·크기·JSON 계약이 손상된 본문을 의존 장애로 거절한다.
     *
     * @param kind 원문 손상 구분
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "size",
            "hash",
            "json",
            "missing",
            "null"
        }
    )
    @DisplayName("본문 크기·체크섬·JSON·필수 영역 오류는 503으로 처리한다")
    void rejectsCorruptedBody(String kind) {
        byte[] bytes = switch (kind) {
            case "json" -> "not-json".getBytes(StandardCharsets.UTF_8);
            case "missing" -> "{\"description\":\"설명\"}".getBytes(StandardCharsets.UTF_8);
            case "null" -> "null".getBytes(StandardCharsets.UTF_8);
            default -> BODY;
        };
        given(store.read(1)).willReturn(snapshot(bytes, "size".equals(kind), "hash".equals(kind)));
        given(reader.read("test-bucket", "body-key", "version-1")).willReturn(bytes);
        assertThatThrownBy(() -> service.read(1))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }

    /**
     * 자료 참조·저장소 조회 실패의 원인을 공통 오류 처리기까지 보존한다.
     *
     * @param metadata 본문 대신 DB 참조 조회를 실패시킬지 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("본문·메타 조회 장애는 같은 원인을 보존한 503으로 처리한다")
    void preservesDependencyFailure(boolean metadata) {
        var cause = new IllegalStateException("자료 조회 실패");
        if (metadata) {
            given(store.read(1)).willThrow(cause);
        } else {
            given(store.read(1)).willReturn(snapshot(BODY, false, false));
            given(reader.read("test-bucket", "body-key", "version-1")).willThrow(cause);
        }
        assertThatThrownBy(() -> service.read(1)).isInstanceOfSatisfying(ProblemException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            assertThat(failure).hasCause(cause);
        });
    }

    /** 기존 ID·문제 검증 실패를 저장소 의존 장애로 바꾸지 않는다. */
    @Test
    @DisplayName("문제 미존재 오류는 원래 404 오류 코드를 유지한다")
    void preservesNotFound() {
        var failure = new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND);
        given(store.read(1)).willThrow(failure);
        assertThatThrownBy(() -> service.read(1)).isSameAs(failure);
        verifyNoInteractions(reader);
    }

    /**
     * 원문 메타 정보와 선택적으로 손상된 크기·해시를 준비한다.
     *
     * @param bytes 검증할 JSON 원문
     * @param wrongSize 크기를 다르게 만들지 여부
     * @param wrongHash 해시를 다르게 만들지 여부
     * @return 본문 참조 스냅샷
     */
    private ProblemHintContextStore.Snapshot snapshot(byte[] bytes, boolean wrongSize, boolean wrongHash) {
        var content = ProblemAssetContent.fromBytes(bytes);
        return new ProblemHintContextStore.Snapshot(
            1,
            2L,
            "현재 통과 제목",
            List.of(),
            new File(
                "body-key",
                "version-1",
                wrongHash ? "0".repeat(64) : content.sha256(),
                content.sizeBytes() + (wrongSize ? 1 : 0)
            )
        );
    }
}
