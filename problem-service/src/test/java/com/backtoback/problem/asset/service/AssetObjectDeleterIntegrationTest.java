package com.backtoback.problem.asset.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;

/** LocalStack의 실제 S3 프로토콜로 버전 삭제와 객체 없음 재시도를 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class AssetObjectDeleterIntegrationTest {
    @Container
    private static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("s3");

    /** 버전과 삭제 마커를 모두 지운 뒤 같은 키를 다시 정리해도 성공한다. */
    @Test
    @DisplayName("실제 S3 호환 저장소에서 모든 버전을 삭제하고 객체 없음 재시도를 허용한다")
    void deletesVersionsAndRetriesMissingObject() {
        try (
            var s3
                = S3Client
                    .builder()
                    .endpointOverride(LOCALSTACK.getEndpoint())
                    .region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(
                        StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()))
                    )
                    .forcePathStyle(true)
                    .build()
        ) {
            String bucket = "cleanup-versions";
            s3.createBucket(request -> request.bucket(bucket));
            s3
                .putBucketVersioning(
                    request -> request
                        .bucket(bucket)
                        .versioningConfiguration(config -> config.status(BucketVersioningStatus.ENABLED))
                );
            s3.putObject(request -> request.bucket(bucket).key("target"), RequestBody.fromString("first"));
            var latest
                = s3.putObject(request -> request.bucket(bucket).key("target"), RequestBody.fromString("second"));
            s3.putObject(request -> request.bucket(bucket).key("target-other"), RequestBody.fromString("preserved"));
            s3.deleteObject(request -> request.bucket(bucket).key("target"));
            var deleter = new AssetObjectDeleter(s3);
            deleter.delete(bucket, "target", latest.versionId(), new AssetCleanupBudget(Clock.systemUTC()));
            deleter.delete(bucket, "target", latest.versionId(), new AssetCleanupBudget(Clock.systemUTC()));
            var remaining = s3.listObjectVersions(request -> request.bucket(bucket));
            assertThat(remaining.versions()).extracting(version -> version.key()).containsExactly("target-other");
            assertThat(remaining.deleteMarkers()).isEmpty();
        }
    }
}
