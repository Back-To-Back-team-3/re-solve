package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.service.ProblemImageUploadTracking.Completion;
import com.backtoback.problem.problem.service.ProblemImageUploadTracking.Receipt;

@ExtendWith(MockitoExtension.class)
class ProblemImageUploadServiceTest {
    @Mock
    private ProblemImageUploadTracking tracking;
    @Mock
    private ProblemImageValidator validator;
    @Mock
    private ProblemAssetObjectWriter writer;
    @Mock
    private ProblemAssetObjectReader reader;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final byte[] bytes
        = {
            1,
            2
        };

    @Test
    @DisplayName("추적 행 생성 후 저장된 원문을 확인하고 완료 응답을 기록한다")
    void completesAfterVerification() {
        Receipt receipt = new Receipt(7L, "object", LocalDateTime.of(2026, 11, 2, 0, 0), null, null);
        given(validator.validate(bytes)).willReturn("image/png");
        given(tracking.start(eq(1L), eq("key"), any(), eq("image/png"))).willReturn(receipt);
        given(writer.put(eq("bucket"), eq("object"), any(), eq("image/png"))).willReturn("v1");
        given(tracking.complete(receipt, "v1")).willReturn(new Completion(201, "{}"));
        var result = service().upload("bucket", 1L, "key", bytes);
        assertThat(result.status()).isEqualTo(201);
        var order = inOrder(tracking, writer, reader);
        order.verify(tracking).start(eq(1L), eq("key"), any(), eq("image/png"));
        order.verify(writer).put(eq("bucket"), eq("object"), any(), eq("image/png"));
        order.verify(reader).verify(eq("bucket"), eq("object"), eq("v1"), any());
        order.verify(tracking).complete(receipt, "v1");
    }

    @Test
    @DisplayName("이미 종결된 요청은 S3에 다시 쓰지 않고 최초 응답을 반환한다")
    void replaysWithoutWriting() {
        given(validator.validate(bytes)).willReturn("image/png");
        given(tracking.start(eq(1L), eq("key"), any(), eq("image/png")))
            .willReturn(new Receipt(7L, "object", LocalDateTime.of(2026, 11, 2, 0, 0), 201, "saved"));
        assertThat(service().upload("bucket", 1L, "key", bytes).json()).isEqualTo("saved");
        verifyNoInteractions(writer, reader);
    }

    @Test
    @DisplayName("쓰기 결과를 알 수 없으면 종료를 기록하지 않는다")
    void retainsUnknownWrite() {
        Receipt receipt = new Receipt(7L, "object", LocalDateTime.of(2026, 11, 2, 0, 0), null, null);
        given(validator.validate(bytes)).willReturn("image/png");
        given(tracking.start(eq(1L), eq("key"), any(), eq("image/png"))).willReturn(receipt);
        given(writer.put(eq("bucket"), eq("object"), any(), eq("image/png"))).willThrow(new IllegalStateException());
        assertThatThrownBy(() -> service().upload("bucket", 1L, "key", bytes)).isInstanceOf(ProblemException.class);
        verify(tracking).unknown(receipt);
        verify(tracking, never()).complete(any(), any());
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("이미지 검증에 실패하면 멱등키를 예약하지 않는다")
    void rejectsBeforeReservingKey() {
        given(validator.validate(bytes))
            .willThrow(
                new ProblemException(com.backtoback.problem.global.error.ProblemErrorCode.PROBLEM_IMAGE_INVALID)
            );
        assertThatThrownBy(() -> service().upload("bucket", 1L, "key", bytes)).isInstanceOf(ProblemException.class);
        verifyNoInteractions(tracking, writer, reader);
    }

    private ProblemImageUploadService service() {
        return new ProblemImageUploadService(tracking, validator, writer, reader, clock);
    }
}
