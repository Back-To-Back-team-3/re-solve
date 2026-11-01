package com.backtoback.problem.problem.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 새 객체 키에 조건부 쓰기를 수행한다. 추적 행과 쓰기 종료 판정은 호출자가 관리한다. */
@Component
@RequiredArgsConstructor
public class ProblemAssetObjectWriter {
    private final S3Client client;

    /**
     * 같은 키의 기존 객체가 있으면 덮어쓰지 않는다. 이 요청의 SDK 재시도를 끄고 단일 시도만 수행한다.
     *
     * @param bucket 대상 버킷
     * @param key 추적 행에 저장된 새 객체 키
     * @param content 저장할 바이트
     * @return S3가 반환한 객체 버전. 버전이 없는 버킷에서는 null
     */
    public String put(String bucket, String key, ProblemAssetContent content) {
        var request
            = PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(key)
                .ifNoneMatch("*")
                .overrideConfiguration(
                    config -> config
                        .addPlugin(
                            clientConfig -> clientConfig
                                .overrideConfiguration(
                                    override -> override.retryStrategy(retry -> retry.maxAttempts(1))
                                )
                        )
                )
                .build();
        return client.putObject(request, RequestBody.fromBytes(content.bytes())).versionId();
    }
}
