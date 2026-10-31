package com.backtoback.judge.judging.client;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.TestDataProperties;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestContent;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import tools.jackson.databind.ObjectMapper;

/** 버전과 체크섬을 검증한 비공개 테스트만 메모리에 읽으며 원문을 로그에 남기지 않는다. */
@Component
@RequiredArgsConstructor
public class TestAssetClient {
    private final S3Client client;
    private final TestDataProperties properties;
    private final ObjectMapper mapper;

    public TestContent read(Asset asset) {
        asset.validate();
        var request
            = GetObjectRequest
                .builder()
                .bucket(properties.bucket())
                .key(asset.objectKey())
                .versionId(asset.objectVersion())
                .build();
        try (var stream = client.getObject(request)) {
            byte[] bytes = stream.readAllBytes();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equalsIgnoreCase(asset.sha256())) {
                throw new IllegalArgumentException("파일 체크섬이 일치하지 않습니다.");
            }
            var content = mapper.readValue(bytes, TestContent.class);
            if (content == null || content.argumentTexts() == null || content.expectedReturnText() == null) {
                throw new IllegalArgumentException("테스트 원문이 올바르지 않습니다.");
            }
            return content;
        } catch (IOException | NoSuchAlgorithmException | SdkException | IllegalArgumentException
            | tools.jackson.core.JacksonException failure) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
