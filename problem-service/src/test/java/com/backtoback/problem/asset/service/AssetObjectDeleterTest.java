package com.backtoback.problem.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

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
@ExtendWith(MockitoExtension.class)
class AssetObjectDeleterTest {
    /** 키가 같은 버전·삭제 마커만 제거하고 접두사가 같은 다른 파일은 보존한다. */
    @Test
    @DisplayName("페이지를 넘겨 정확한 키의 모든 버전과 삭제 마커만 삭제한다")
    void removesExactVersions() {
        var s3 = mock(S3Client.class);
        given(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .willReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        given(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .willReturn(
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(true)
                    .nextKeyMarker("key")
                    .nextVersionIdMarker("v1")
                    .versions(ObjectVersion.builder().key("key").versionId("v1").build())
                    .build()
            )
            .willReturn(
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
            .willReturn(ListObjectVersionsResponse.builder().isTruncated(false).build());
        given(s3.deleteObjects(any(DeleteObjectsRequest.class))).willReturn(DeleteObjectsResponse.builder().build());
        new AssetObjectDeleter(s3)
            .delete("bucket", "key", "v2", new AssetCleanupBudget(Clock.systemUTC())::requestTimeout);
        var pages = ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3, times(3)).listObjectVersions(pages.capture());
        assertThat(pages.getAllValues().get(1).keyMarker()).isEqualTo("key");
        assertThat(pages.getAllValues().get(1).versionIdMarker()).isEqualTo("v1");
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
        given(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .willReturn(GetBucketVersioningResponse.builder().build());
        var deleter = new AssetObjectDeleter(s3);
        deleter.delete("bucket", "key", null, new AssetCleanupBudget(Clock.systemUTC())::requestTimeout);
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        given(s3.deleteObject(any(DeleteObjectRequest.class)))
            .willThrow(S3Exception.builder().statusCode(403).message("denied").build());
        assertThatThrownBy(
            () -> deleter.delete("bucket", "key", null, new AssetCleanupBudget(Clock.systemUTC())::requestTimeout)
        ).isInstanceOf(S3Exception.class);
    }

    /** HTTP 성공이어도 부분 삭제 오류가 있으면 DB 정리로 진행하지 않는다. */
    @Test
    @DisplayName("버전 삭제 부분 실패는 성공으로 처리하지 않는다")
    void partialFailure() {
        var s3 = mock(S3Client.class);
        given(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .willReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.SUSPENDED).build());
        given(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .willReturn(
                ListObjectVersionsResponse
                    .builder()
                    .versions(ObjectVersion.builder().key("key").versionId("null").build())
                    .build()
            );
        given(s3.deleteObjects(any(DeleteObjectsRequest.class)))
            .willReturn(DeleteObjectsResponse.builder().errors(S3Error.builder().code("AccessDenied").build()).build());
        assertThatThrownBy(
            () -> new AssetObjectDeleter(s3)
                .delete("bucket", "key", null, new AssetCleanupBudget(Clock.systemUTC())::requestTimeout)
        ).isInstanceOf(IllegalStateException.class);
    }

    /** 전체 예산이 소진되면 같은 파일의 다음 페이지나 삭제를 시작하지 않는다. */
    @Test
    @DisplayName("S3 응답 도중 잠금 기한을 지나면 후속 버전 조회와 삭제를 중단한다")
    void stopsAfterDeadline() {
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-09T00:00:00Z");
        given(clock.instant()).willReturn(now);
        var budget = new AssetCleanupBudget(clock);
        var s3 = mock(S3Client.class);
        given(s3.getBucketVersioning(any(GetBucketVersioningRequest.class))).willAnswer(call -> {
            given(clock.instant()).willReturn(now.plus(Duration.ofMinutes(31)));
            return GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build();
        });
        assertThatThrownBy(() -> new AssetObjectDeleter(s3).delete("bucket", "key", "v1", budget::requestTimeout))
            .isInstanceOf(IllegalStateException.class);
        verify(s3, never()).listObjectVersions(any(ListObjectVersionsRequest.class));
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    /** 대상 키를 지난 뒤에는 같은 접두사의 다른 객체를 조회하거나 삭제하지 않는다. */
    @Test
    @DisplayName("목록 마커가 대상 키를 지나면 남은 페이지를 건너뛴다")
    void stopsAfterTargetKey() {
        var s3 = mock(S3Client.class);
        given(s3.getBucketVersioning(any(GetBucketVersioningRequest.class)))
            .willReturn(GetBucketVersioningResponse.builder().status(BucketVersioningStatus.ENABLED).build());
        var sibling = ObjectVersion.builder().key("key-other").versionId("other").build();
        given(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
            .willReturn(
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(true)
                    .nextKeyMarker("key-other")
                    .versions(ObjectVersion.builder().key("key").versionId("v1").build(), sibling)
                    .build(),
                ListObjectVersionsResponse
                    .builder()
                    .isTruncated(true)
                    .nextKeyMarker("key-other")
                    .versions(sibling)
                    .build()
            );
        given(s3.deleteObjects(any(DeleteObjectsRequest.class))).willReturn(DeleteObjectsResponse.builder().build());
        new AssetObjectDeleter(s3).delete("bucket", "key", "v1", () -> Duration.ofSeconds(10));
        verify(s3, times(2)).listObjectVersions(any(ListObjectVersionsRequest.class));
        var request = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(request.capture());
        assertThat(request.getValue().delete().objects()).hasSize(1);
        assertThat(request.getValue().delete().objects().getFirst().key()).isEqualTo("key");
    }

    /** 최종 구간의 HTTP 호출이 잔여 예산을 넘지 않도록 한다. */
    @Test
    @DisplayName("잔여 예산이 10초 미만이면 S3 호출 제한도 함께 줄인다")
    void capsTimeoutByRemainingBudget() {
        var clock = mock(Clock.class);
        var now = Instant.parse("2026-11-09T00:00:00Z");
        given(clock.instant()).willReturn(now);
        var budget = new AssetCleanupBudget(clock);
        given(clock.instant()).willReturn(now.plusSeconds(898));
        assertThat(budget.requestTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(budget::requireDatabaseTime).isInstanceOf(IllegalStateException.class);
        given(clock.instant()).willReturn(now.plusSeconds(900).minusNanos(1));
        assertThatThrownBy(budget::requestTimeout).isInstanceOf(IllegalStateException.class);
    }

}
