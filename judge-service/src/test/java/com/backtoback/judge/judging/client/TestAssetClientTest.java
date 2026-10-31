package com.backtoback.judge.judging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.TestDataProperties;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TestAssetClientTest {
    @Mock
    private S3Client s3;

    @Test
    @DisplayName("고정 객체 버전과 체크섬을 확인하고 JSON 문자열의 정밀도를 보존한다")
    void readsFixedVersion() throws Exception {
        byte[] bytes
            = "{\"argumentTexts\":{\"value\":\"9223372036854775807\"},\"expectedReturnText\":\"1\"}"
                .getBytes(StandardCharsets.UTF_8);
        given(s3.getObject(any(GetObjectRequest.class))).willReturn(stream(bytes));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var client = new TestAssetClient(s3, new TestDataProperties("bucket"), JsonMapper.builder().build());
        assertThat(client.read(new Asset("key", "v1", hash)).argumentTexts().get("value"))
            .isEqualTo("9223372036854775807");
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        assertThat(request.getValue().versionId()).isEqualTo("v1");
    }

    @Test
    @DisplayName("체크섬이 다른 원문은 실행 자료로 사용하지 않는다")
    void rejectsChangedContent() {
        given(s3.getObject(any(GetObjectRequest.class))).willReturn(stream("{}".getBytes(StandardCharsets.UTF_8)));
        var client = new TestAssetClient(s3, new TestDataProperties("bucket"), JsonMapper.builder().build());
        assertThatThrownBy(() -> client.read(new Asset("key", "v1", "a".repeat(64))))
            .isInstanceOf(JudgeException.class);
    }

    private ResponseInputStream<GetObjectResponse> stream(byte[] bytes) {
        return new ResponseInputStream<>(
            GetObjectResponse.builder().build(),
            AbortableInputStream.create(new ByteArrayInputStream(bytes))
        );
    }
}
