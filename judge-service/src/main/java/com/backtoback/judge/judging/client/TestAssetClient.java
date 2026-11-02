package com.backtoback.judge.judging.client;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.config.TestDataProperties;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.Asset;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse.TestContent;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import tools.jackson.databind.ObjectMapper;

/** 고정 버전·체크섬을 검증하고 요청부터 본문 읽기까지 제한 시간 안에 마친다. */
@Component
public class TestAssetClient {
    private final S3Client client;
    private final TestDataProperties properties;
    private final ObjectMapper mapper;
    private final Duration timeout;

    /**
     * 임대 120초·갱신 간격 30초보다 짧은 20초의 전체 읽기 제한을 적용한다.
     *
     * @param client 공통 AWS 설정을 사용하는 클라이언트
     * @param properties 테스트 버킷 설정
     * @param mapper 테스트 원문 변환기
     */
    @Autowired
    public TestAssetClient(S3Client client, TestDataProperties properties, ObjectMapper mapper) {
        this(client, properties, mapper, Duration.ofSeconds(20));
    }

    /**
     * 전체 읽기 제한을 지정한 클라이언트를 구성한다.
     *
     * @param client S3 클라이언트
     * @param properties 테스트 버킷 설정
     * @param mapper 테스트 원문 변환기
     * @param timeout 요청·본문 읽기·검증 전체 제한 시간
     */
    TestAssetClient(S3Client client, TestDataProperties properties, ObjectMapper mapper, Duration timeout) {
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /**
     * 원문을 제한 시간 안에 읽고 검증한다. 시간 초과·중단 시 응답을 배수하지 않고 연결을 중단한다.
     *
     * @param asset 고정 객체 버전과 체크섬
     * @return 검증한 테스트 원문
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 읽기 시간 초과·조회·검증 실패
     * @throws InterruptedException 호출 스레드가 중단된 경우
     */
    public TestContent read(Asset asset) throws InterruptedException {
        asset.validate();
        var stopped = new AtomicBoolean();
        var stream = new AtomicReference<ResponseInputStream<GetObjectResponse>>();
        var task = new FutureTask<>(() -> {
            var request
                = GetObjectRequest
                    .builder()
                    .bucket(properties.bucket())
                    .key(asset.objectKey())
                    .versionId(asset.objectVersion())
                    .overrideConfiguration(config -> config.apiCallTimeout(timeout))
                    .build();
            var response = client.getObject(request);
            stream.set(response);
            try {
                // 취소와 응답 수신이 경합해도 뒤늦게 받은 연결을 유지하지 않는다.
                if (stopped.get()) {
                    throw new IOException("읽기가 중단되었습니다.");
                }
                return decode(response.readAllBytes(), asset.sha256());
            } finally {
                response.abort();
            }
        });
        var context = MDC.getCopyOfContextMap();
        Thread.startVirtualThread(() -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                task.run();
            } finally {
                MDC.clear();
            }
        });
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } catch (ExecutionException | TimeoutException failure) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        } finally {
            stopped.set(true);
            task.cancel(true);
            var response = stream.get();
            if (response != null) {
                response.abort();
            }
        }
    }

    /**
     * 체크섬을 대조하고 JSON 문자열의 정밀도를 보존하여 변환한다.
     *
     * @param bytes 수신한 전체 원문
     * @param expectedHash 고정 회차의 SHA-256 체크섬
     * @return 테스트 입력·기대값
     * @throws NoSuchAlgorithmException SHA-256을 사용할 수 없는 경우
     * @throws IllegalArgumentException 체크섬이나 원문 구조가 유효하지 않은 경우
     */
    private TestContent decode(byte[] bytes, String expectedHash) throws NoSuchAlgorithmException {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equalsIgnoreCase(expectedHash)) {
            throw new IllegalArgumentException("파일 체크섬이 일치하지 않습니다.");
        }
        var content = mapper.readValue(bytes, TestContent.class);
        if (content == null || content.argumentTexts() == null || content.expectedReturnText() == null) {
            throw new IllegalArgumentException("테스트 원문이 올바르지 않습니다.");
        }
        return content;
    }
}
