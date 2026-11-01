package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.problem.problem.service.ProblemAssetUploadTracking.UploadTicket;

/** 추적, 외부 저장과 결과 기록의 순서와 실패 경계를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetUploadServiceTest {
    @Mock
    private ProblemAssetUploadTracking tracking;
    @Mock
    private ProblemAssetObjectWriter writer;
    @Mock
    private ProblemAssetObjectReader reader;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    /** 성공 경로가 추적, 저장, 확인, 완료 순으로 실행되는지 확인한다. */
    @Test
    @DisplayName("저장 결과를 확인한 뒤 업로드 완료를 기록한다")
    void recordsVerifiedUpload() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );
        var ticket = new UploadTicket(1L, "key", LocalDateTime.of(2026, 11, 2, 0, 0), true);
        given(tracking.start(2L, content, true)).willReturn(ticket);
        given(writer.put("bucket", "key", content)).willReturn("version");
        given(tracking.complete(ticket, "version")).willReturn(true);
        var service = new ProblemAssetUploadService(tracking, writer, reader, clock);

        // when
        Long id = service.upload("bucket", 2L, content, true);

        // then
        var order = inOrder(tracking, writer, reader);
        order.verify(tracking).start(2L, content, true);
        order.verify(writer).put("bucket", "key", content);
        order.verify(reader).verify("bucket", "key", "version", content);
        order.verify(tracking).complete(ticket, "version");
        assertThat(id).isEqualTo(1L);
    }

    /** 쓰기 오류가 종료 확인으로 기록되지 않는지 확인한다. */
    @Test
    @DisplayName("쓰기 결과가 불명확하면 완료하지 않고 추적 행을 보존한다")
    void preservesUnknown() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );
        var ticket = new UploadTicket(1L, "key", LocalDateTime.of(2026, 11, 2, 0, 0), false);
        given(tracking.start(2L, content, false)).willReturn(ticket);
        given(writer.put("bucket", "key", content)).willThrow(new IllegalStateException());
        var service = new ProblemAssetUploadService(tracking, writer, reader, clock);

        // when & then
        assertThatThrownBy(() -> service.upload("bucket", 2L, content, false))
            .isInstanceOf(IllegalStateException.class);
        verify(tracking).recordUnknown(ticket);
        verify(tracking, never()).complete(ticket, null);
    }
}
