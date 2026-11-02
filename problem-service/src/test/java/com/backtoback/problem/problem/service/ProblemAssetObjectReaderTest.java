package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 실제 읽은 바이트와 지정한 객체 버전의 검증 경계를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetObjectReaderTest {
    @Mock
    private S3Client client;

    /** 버전을 요청에 전달하고 동일한 바이트를 허용하는지 확인한다. */
    @Test
    @DisplayName("저장된 객체의 버전과 바이트가 일치하면 확인에 성공한다")
    void verifiesVersionAndBytes() {
        // given
        byte[] bytes = "본문".getBytes(StandardCharsets.UTF_8);
        given(client.getObjectAsBytes(any(GetObjectRequest.class)))
            .willReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes));
        var reader = new ProblemAssetObjectReader(client);

        // when
        reader.verify("bucket", "key", "v1", ProblemAssetContent.fromBytes(bytes));

        // then
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObjectAsBytes(request.capture());
        assertThat(request.getValue().versionId()).isEqualTo("v1");
    }

    /** 같은 크기의 다른 바이트도 저장 성공으로 취급하지 않는지 확인한다. */
    @Test
    @DisplayName("객체의 바이트가 다르면 검증을 거절한다")
    void rejectsDifferentBytes() {
        // given
        given(client.getObjectAsBytes(any(GetObjectRequest.class)))
            .willReturn(
                ResponseBytes
                    .fromByteArray(
                        GetObjectResponse.builder().build(),
                        new byte[] {
                            1,
                            2
                        }
                    )
            );
        var reader = new ProblemAssetObjectReader(client);

        // when & then
        assertThatThrownBy(
            () -> reader
                .verify(
                    "bucket",
                    "key",
                    null,
                    ProblemAssetContent
                        .fromBytes(
                            new byte[] {
                                1,
                                3
                            }
                        )
                )
        ).isInstanceOf(IllegalStateException.class);
    }
}
