package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeStarterCodeClient;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;

import tools.jackson.databind.json.JsonMapper;

/** 본문과 이미지 조회 실패의 원인이 공통 예외 처리기까지 보존되는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ProblemContentServiceFailureTest {
    /**
     * 저장소 읽기 예외를 의존 장애의 원인으로 보존한다.
     *
     * @param image 이미지 조회 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("본문·이미지 읽기 실패를 503 오류 코드로 변환하고 동일한 원인을 보존한다")
    void preservesStorageFailure(boolean image) {
        var store = mock(ProblemContentStore.class);
        var reader = mock(ProblemAssetObjectReader.class);
        var file = new File("object-key", "version-1", "a".repeat(64), 1);
        if (image) {
            given(store.image(1L, 2L, 3L, "EXAM:1")).willReturn(new ProblemContentStore.Image("image/png", file));
        } else {
            given(store.content(1L, 2L, "EXAM:1")).willReturn(new ProblemContentStore.Snapshot(null, file, List.of()));
        }
        var cause = new IllegalStateException("저장소 조회 실패");
        given(reader.read("test-bucket", "object-key", "version-1")).willThrow(cause);
        var service
            = new ProblemContentService(
                store,
                reader,
                new ProblemImageStorageProperties("test-bucket"),
                mock(JudgeStarterCodeClient.class),
                JsonMapper.builder().build()
            );
        assertThatThrownBy(() -> {
            if (image) {
                service.image(1L, 2L, 3L, "EXAM:1");
            } else {
                service.read(1L, 2L, "EXAM:1");
            }
        }).isInstanceOfSatisfying(ProblemException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            assertThat(failure).hasCause(cause);
        });
    }
}
