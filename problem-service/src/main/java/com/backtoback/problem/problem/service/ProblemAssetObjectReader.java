package com.backtoback.problem.problem.service;

import java.util.Arrays;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * 고정 객체를 읽고 예상 바이트와 메타 정보를 대조한다.
 * 기준 문서: API 명세서 v1.1 / §2.3.0 문제·테스트 공통 데이터 형식: 저장 결과 확인.
 */
@Component
@RequiredArgsConstructor
public class ProblemAssetObjectReader {
    private final S3Client client;

    /**
     * 지정한 버전을 읽어 실제 바이트를 검사한다. 버전이 null이면 버전 없는 객체를 조회한다.
     *
     * @param bucket 객체 버킷
     * @param key 객체 키
     * @param version 저장 결과의 객체 버전
     * @param expected 기대 바이트와 메타 정보
     * @throws IllegalStateException 읽은 내용이 기대값과 다른 경우
     */
    public void verify(String bucket, String key, String version, ProblemAssetContent expected) {
        if (!matches(bucket, key, version, expected)) {
            throw new IllegalStateException("저장된 파일이 입력과 일치하지 않습니다.");
        }
    }

    /**
     * 객체 원문과 메타 정보를 대조하며 저장소 오류는 호출자에게 전달한다.
     *
     * @param bucket 조회할 버킷
     * @param key 조회할 불변 객체 키
     * @param version 저장된 객체 버전. 버전 지정이 없으면 null
     * @param expected 비교할 원문과 메타 정보
     * @return 크기·체크섬·바이트가 모두 일치하면 true
     */
    public boolean matches(String bucket, String key, String version, ProblemAssetContent expected) {
        var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
        var response = client.getObjectAsBytes(request);
        var actual = ProblemAssetContent.fromBytes(response.asByteArray());
        return actual.sizeBytes() == expected.sizeBytes() && actual.sha256().equals(expected.sha256())
            && Arrays.equals(actual.bytes(), expected.bytes());
    }

    /** 지정된 불변 객체의 원문을 읽는다. */
    public byte[] read(String bucket, String key, String version) {
        var request = GetObjectRequest.builder().bucket(bucket).key(key).versionId(version).build();
        return client.getObjectAsBytes(request).asByteArray();
    }
}
