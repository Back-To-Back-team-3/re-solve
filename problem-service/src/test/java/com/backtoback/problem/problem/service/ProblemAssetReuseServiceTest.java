package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

/** 후보 파일의 원문 일치와 저장소 오류를 구분한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetReuseServiceTest {
    @Mock
    private ProblemBodyAssetRepository bodies;
    @Mock
    private ProblemTestAssetRepository tests;
    @Mock
    private ProblemAssetObjectReader reader;

    /** 메타 정보가 같아도 원문이 다르면 재사용하지 않는다. */
    @Test
    @DisplayName("후보의 원문이 다르면 재사용 결과가 없다")
    void rejectsDifferentBytes() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );
        var asset = ProblemBodyAsset.create(1L, "key", null, content.sha256(), 1, LocalDateTime.now());
        ReflectionTestUtils.setField(asset, "id", 3L);
        given(
            bodies
                .findByProblemIdAndOwnerIdAndStatusAndSha256AndSizeBytesOrderByIdAsc(
                    any(),
                    any(),
                    any(),
                    any(),
                    anyLong()
                )
        ).willReturn(List.of(asset));
        given(reader.matches("bucket", "key", null, content)).willReturn(false);
        var service = new ProblemAssetReuseService(bodies, tests, reader);

        // when & then
        assertThat(service.find("bucket", 1L, 1L, content, true)).isEmpty();
    }

    /** 저장소 접근 오류는 원문 불일치로 처리하지 않는다. */
    @Test
    @DisplayName("객체 조회가 실패하면 재사용 조회도 실패한다")
    void propagatesStorageFailure() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );
        var asset = ProblemBodyAsset.create(1L, "key", null, content.sha256(), 1, LocalDateTime.now());
        given(
            bodies
                .findByProblemIdAndOwnerIdAndStatusAndSha256AndSizeBytesOrderByIdAsc(
                    any(),
                    any(),
                    any(),
                    any(),
                    anyLong()
                )
        ).willReturn(List.of(asset));
        given(reader.matches("bucket", "key", null, content)).willThrow(new IllegalStateException("storage"));
        var service = new ProblemAssetReuseService(bodies, tests, reader);

        // when & then
        assertThatThrownBy(() -> service.find("bucket", 1L, 1L, content, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("storage");
    }
}
