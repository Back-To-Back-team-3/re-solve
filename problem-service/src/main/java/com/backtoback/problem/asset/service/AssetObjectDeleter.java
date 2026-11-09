package com.backtoback.problem.asset.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

/** 쓰기 종료가 확인된 불변 키의 실제 버전과 삭제 마커만 제거한다. */
@Component
@RequiredArgsConstructor
public class AssetObjectDeleter {
    private final S3Client s3;

    /**
     * 버전 미사용 버킷은 키를, 버전 사용 이력이 있으면 모든 정확한 키 버전을 삭제한다.
     * 목록·권한·삭제 결과를 확인하지 못하면 성공으로 반환하지 않는다.
     *
     * @param bucket 저장 버킷
     * @param key 삭제 선점된 불변 키
     * @param knownVersion 저장 당시 버전
     * @param requestTimeout 각 S3 요청의 소유권을 검사하고 제한 시간을 반환하는 공급자
     * @throws RuntimeException S3 실패·부분 삭제·불완전한 버전 목록
     */
    public void delete(String bucket, String key, String knownVersion, Supplier<Duration> requestTimeout) {
        var versioning
            = s3
                .getBucketVersioning(
                    GetBucketVersioningRequest
                        .builder()
                        .bucket(bucket)
                        .overrideConfiguration(config -> config.apiCallTimeout(requestTimeout.get()))
                        .build()
                );
        if (versioning.status() == null && knownVersion == null) {
            s3
                .deleteObject(
                    DeleteObjectRequest
                        .builder()
                        .bucket(bucket)
                        .key(key)
                        .overrideConfiguration(config -> config.apiCallTimeout(requestTimeout.get()))
                        .build()
                );
            return;
        }
        var versions = versions(bucket, key, requestTimeout);
        for (int start = 0; start < versions.size(); start += 1000) {
            var batch = versions.subList(start, Math.min(start + 1000, versions.size()));
            var result
                = s3
                    .deleteObjects(
                        DeleteObjectsRequest
                            .builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(batch).quiet(true).build())
                            .overrideConfiguration(config -> config.apiCallTimeout(requestTimeout.get()))
                            .build()
                    );
            if (!result.errors().isEmpty()) {
                throw new IllegalStateException("파일의 일부 S3 버전을 삭제하지 못했습니다.");
            }
        }
        if (!versions(bucket, key, requestTimeout).isEmpty()) {
            throw new IllegalStateException("삭제 후 S3 버전이 남아 있습니다.");
        }
    }

    /**
     * 접두사가 같은 다른 파일은 제외하고 정확한 키의 버전과 마커를 모은다.
     *
     * @param bucket 저장 버킷
     * @param key 불변 키
     * @param requestTimeout 각 S3 요청의 소유권을 검사하고 제한 시간을 반환하는 공급자
     * @return 실제 삭제할 버전
     * @throws IllegalStateException 과도하거나 불완전한 버전 목록
     */
    private List<ObjectIdentifier> versions(String bucket, String key, Supplier<Duration> requestTimeout) {
        var objects = new ArrayList<ObjectIdentifier>();
        String keyMarker = null;
        String versionMarker = null;
        for (int page = 0; page < 10; page++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("파일 삭제가 중단되었습니다.");
            }
            var result
                = s3
                    .listObjectVersions(
                        ListObjectVersionsRequest
                            .builder()
                            .bucket(bucket)
                            .prefix(key)
                            .keyMarker(keyMarker)
                            .versionIdMarker(versionMarker)
                            .maxKeys(1000)
                            .overrideConfiguration(config -> config.apiCallTimeout(requestTimeout.get()))
                            .build()
                    );
            result
                .versions()
                .stream()
                .filter(version -> key.equals(version.key()))
                .forEach(version -> objects.add(identifier(key, version.versionId())));
            result
                .deleteMarkers()
                .stream()
                .filter(marker -> key.equals(marker.key()))
                .forEach(marker -> objects.add(identifier(key, marker.versionId())));
            if (!Boolean.TRUE.equals(result.isTruncated())) {
                return objects;
            }
            keyMarker = result.nextKeyMarker();
            versionMarker = result.nextVersionIdMarker();
            if (keyMarker == null) {
                throw new IllegalStateException("S3 버전 목록의 다음 위치가 없습니다.");
            }
        }
        throw new IllegalStateException("파일 버전 목록이 한 건의 정리 범위를 초과했습니다.");
    }

    /**
     * 버전을 생략해 삭제 마커만 생성하지 않도록 식별자를 검증한다.
     *
     * @param key 객체 키
     * @param version 실제 버전(null 버전은 문자열 'null')
     * @return 삭제 식별자
     * @throws IllegalStateException 버전 누락
     */
    private ObjectIdentifier identifier(String key, String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("실제 S3 버전이 없습니다.");
        }
        return ObjectIdentifier.builder().key(key).versionId(version).build();
    }
}
