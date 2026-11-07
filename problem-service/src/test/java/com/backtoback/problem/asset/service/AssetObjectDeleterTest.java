package com.backtoback.problem.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.DeleteMarkerEntry;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 실제 버전 삭제와 불명확한 응답 보존을 검증한다. */
class AssetObjectDeleterTest {
    /** 키가 같은 버전·삭제 마커만 제거하고 접두사가 같은 다른 파일은 보존한다. */
    @Test
    @DisplayName("페이지를 넘겨 정확한 키의 모든 버전과 삭제 마커만 삭제한다")
    void removesExactVersions() {
        var s3 = mock(S3Client.class);
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .thenReturn(
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(true)
                    .nextKeyMarker("key")
                    .nextVersionIdMarker("v1")
                    .versions(ObjectVersion.builder().key("key").versionId("v1").build())
                    .build()
            )
            .thenReturn(
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(false)
                    .versions(
                        ObjectVersion.builder().key("key-other").versionId("other").build(),
                        ObjectVersion.builder().key("key").versionId("v2").build()
                    )
                    .deleteMarkers(DeleteMarkerEntry.builder().key("key").versionId("marker").build())
                    .build()
            )
            .thenReturn(ListObjectVersionsResponse.builder().isTruncated(false).build());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        new AssetObjectDeleter(s3).delete("bucket", "key", "v2");
        var request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(request.capture());
        assertThat(request.getValue().delete().objects())
            .extracting(value -> value.versionId())
            .containsExactly("v1", "v2", "marker");
        assertThat(request.getValue().delete().objects()).allMatch(value -> value.key().equals("key"));
    }

    /** 버전 미사용 키의 반복 삭제는 안전하고 권한 장애는 성공으로 취급하지 않는다. */
    @Test
    @DisplayName("버전 미사용 파일의 삭제는 반복 가능하지만 권한 오류는 전달한다")
    void unversionedAndFailure() {
        var s3 = mock(S3Client.class);
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder().build());
        var deleter = new AssetObjectDeleter(s3);
        deleter.delete("bucket", "key", null);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
            .thenThrow(S3Exception.builder().statusCode(403).message("denied").build());
        assertThatThrownBy(() -> deleter.delete("bucket", "key", null)).isInstanceOf(S3Exception.class);
    }

    /** HTTP 성공이어도 부분 삭제 오류가 있으면 DB 정리로 진행하지 않는다. */
    @Test
    @DisplayName("버전 삭제 부분 실패는 성공으로 처리하지 않는다")
    void partialFailure() {
        var s3 = mock(S3Client.class);
        when(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .thenReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.SUSPENDED).build());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .thenReturn(
                ListObjectVersionsResponse
                    .builder()
                    .versions(ObjectVersion.builder().key("key").versionId("null").build())
                    .build()
            );
        when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
            .thenReturn(DeleteObjectsResponse.builder().errors(S3Error.builder().code("AccessDenied").build()).build());
        assertThatThrownBy(() -> new AssetObjectDeleter(s3).delete("bucket", "key", null))
            .isInstanceOf(IllegalStateException.class);
    }
}
