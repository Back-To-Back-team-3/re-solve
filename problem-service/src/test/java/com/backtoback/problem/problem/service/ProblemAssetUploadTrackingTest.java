package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

/** 업로드 추적의 시작과 미확인 상태를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetUploadTrackingTest {
    @Mock
    private ProblemBodyAssetRepository bodies;
    @Mock
    private ProblemTestAssetRepository tests;

    /** 추적 파일의 키와 종료 미확인 상태가 유지되는지 확인한다. */
    @Test
    @DisplayName("본문 추적 시작 후 결과가 불명확하면 종료 시각을 기록하지 않는다")
    void preservesUnknownBody() {
        // given
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
        given(bodies.saveAndFlush(any(ProblemBodyAsset.class))).willAnswer(invocation -> {
            ProblemBodyAsset asset = invocation.getArgument(0);
            ReflectionTestUtils.setField(asset, "id", 1L);
            return asset;
        });
        var tracking = new ProblemAssetUploadTracking(bodies, tests, clock, null);
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );

        // when
        var ticket = tracking.start(1L, content, true);
        var asset
            = ProblemBodyAsset.create(1L, ticket.key(), null, content.sha256(), content.sizeBytes(), ticket.expiry());
        given(bodies.findByIdForUpdate(1L)).willReturn(Optional.of(asset));
        tracking.recordUnknown(ticket);

        // then
        assertThat(ticket.id()).isEqualTo(1L);
        assertThat(ticket.key()).startsWith("problem-body/");
        assertThat(asset.getStatus()).isEqualTo(ProblemAssetStatus.PENDING);
        assertThat(asset.getUploadFinishedAt()).isNull();
        assertThat(asset.getFailureCode()).isEqualTo("UPLOAD_UNCONFIRMED");
    }
}
