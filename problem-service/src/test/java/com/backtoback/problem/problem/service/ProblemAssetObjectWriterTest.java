package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** 조건부 쓰기 요청과 S3 응답을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ProblemAssetObjectWriterTest {
    @Mock
    private S3Client client;

    /** 기존 객체 덮어쓰기 방지 조건과 버전 반환을 확인한다. */
    @Test
    @DisplayName("새 객체 키에 조건부로 저장하고 응답 버전을 반환한다")
    void putsWithCondition() {
        // given
        given(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .willReturn(PutObjectResponse.builder().versionId("version").build());
        var writer = new ProblemAssetObjectWriter(client);

        // when
        String version
            = writer
                .put(
                    "bucket",
                    "key",
                    ProblemAssetContent
                        .fromBytes(
                            new byte[] {
                                1
                            }
                        )
                );

        // then
        var request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(version).isEqualTo("version");
    }
}
